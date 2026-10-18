package com.kira3.ametrine.data

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kira3.ametrine.R
import com.kira3.ametrine.data.db.GameDao
import com.kira3.ametrine.data.db.GameEntity
import com.kira3.ametrine.emucore.EmulatorCore
import com.kira3.ametrine.emulation.EmulationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Game(
    val uri: Uri,
    val fileName: String,
    val serial: String?,
    /** Whether the core has already tried to read [serial]. */
    val serialRead: Boolean,
    val sizeBytes: Long,
    /** The GameDB name for [serial], if the database knows the game. */
    val dbTitle: String? = null,
    /** The serial of the first disc of the game's disc set, or null for a game on one disc. */
    val groupSerial: String? = null,
    /** The GameDB name of the disc set. */
    val groupTitle: String? = null,
    /** Where the disc comes in its disc set. */
    val discIndex: Int = 0,
    val lastModified: Long = 0,
    val record: GameRecord = GameRecord(),
    /** Whether "Exit and resume later" left a state for the next start. */
    val resumable: Boolean = false,
) {
    /** What the game's data (settings, cheats, states, records) is kept under: the same for every disc of a set. */
    val dataSerial: String? get() = groupSerial ?: serial

    /**
     * The user's title, else the disc set's name, else the GameDB name, else the file name without
     * its extension. A playlist goes by its file name: the GameDB name is its first disc's.
     */
    val title: String
        get() = record.customTitle ?: groupTitle ?: dbTitle?.takeIf { format != "M3U" } ?: discTitle

    /** This disc's own name, e.g. "Final Fantasy VII (Disc 2)", whatever the user called the game. */
    val discTitle: String get() = dbTitle?.takeIf { format != "M3U" } ?: fileName.substringBeforeLast('.')

    /** Key in [GameRecordRepository]. */
    val recordKey: String get() = GameRecordRepository.keyOf(dataSerial, uri.toString())

    /** Upper-case extension, e.g. "CHD". */
    val format: String get() = fileName.substringAfterLast('.', "").uppercase()

    /** Disc images carry a serial for the core to read; executables and PSFs have none. */
    private val needsSerial: Boolean get() = format !in SERIAL_LESS_FORMATS

    /** Shown in the library: the core knows the disc, or the file isn't a disc at all. */
    val recognized: Boolean get() = !needsSerial || serial != null

    /** The core has looked and found no Saturn disc: a BIOS, another system's image or some other file. Kept under its own heading. */
    val unrecognized: Boolean get() = needsSerial && serialRead && serial == null

    private companion object {
        val SERIAL_LESS_FORMATS = setOf("EXE", "PSEXE", "PS-EXE", "PSF", "MINIPSF")
    }
}

/** How the library orders its games. Ties fall back to the title. */
enum class LibrarySort {
    RecentlyPlayed, Title, PlayTime, RecentlyAdded, Size;

    fun comparator(): Comparator<Game> {
        val byTitle = compareBy<Game> { it.title.lowercase() }
        return when (this) {
            Title -> byTitle
            RecentlyPlayed -> compareByDescending<Game> { it.record.lastPlayed }.then(byTitle)
            PlayTime -> compareByDescending<Game> { it.record.playSeconds }.then(byTitle)
            RecentlyAdded -> compareByDescending<Game> { it.lastModified }.then(byTitle)
            Size -> compareByDescending<Game> { it.sizeBytes }.then(byTitle)
        }
    }

    companion object {
        fun fromName(name: String?): LibrarySort = entries.firstOrNull { it.name == name } ?: RecentlyPlayed
    }
}

/**
 * Games in the folder the user picked through SAF. The list lives in Room; [refresh]
 * brings it up to date with the folder.
 */
class LibraryRepository(
    context: Context,
    private val core: EmulatorCore,
    private val preferences: AppPreferences,
    private val dao: GameDao,
    private val records: GameRecordRepository,
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val refreshLock = Mutex()
    private val _refreshing = MutableStateFlow(false)

    /** SAF tree URI of the game folder, or null before one is picked. */
    val gameFolder: Flow<Uri?> = preferences.gameFolder

    private val resumeChanges = MutableStateFlow(0)

    /** Cached games with their records, in no particular order; see [LibrarySort]. */
    val games: Flow<List<Game>> = combine(dao.observeAll(), records.records, resumeChanges) { rows, records, _ ->
        val resumable = withContext(Dispatchers.IO) { core.resumableSerials() }
        rows.map { row ->
            val game = row.toGame()
            game.copy(record = records[game.recordKey] ?: GameRecord(), resumable = game.dataSerial in resumable)
        }
    }

    /** Re-reads what the :emu process wrote (play time, suspend states) while the game was running. */
    fun reloadRecords() {
        records.refresh()
        resumeChanges.value++
    }

    suspend fun setFavorite(game: Game, favorite: Boolean) =
        records.update(game.recordKey) { it.copy(favorite = favorite) }

    /** A blank [title] goes back to the GameDB name or file name. */
    suspend fun setCustomTitle(game: Game, title: String) =
        records.update(game.recordKey) { it.copy(customTitle = title.trim().ifEmpty { null }) }

    /** Drops the suspended game of [dataSerial] (see [Game.dataSerial]), so the next start begins at the start. */
    suspend fun discardSuspendState(dataSerial: String) {
        withContext(Dispatchers.IO) { core.suspendStateFile(dataSerial)?.delete() }
        resumeChanges.value++
    }

    /** Called when a game is started, so "recently played" is right before the first play time arrives. */
    suspend fun markPlayed(game: Game) =
        records.update(game.recordKey) { it.copy(lastPlayed = System.currentTimeMillis(), lastDisc = game.uri.toString()) }

    val sort: Flow<LibrarySort> = preferences.librarySort.map { LibrarySort.fromName(it) }

    suspend fun setSort(sort: LibrarySort) = preferences.setLibrarySort(sort.name)

    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Keeps read access to [treeUri] across restarts and makes it the game folder. */
    suspend fun setGameFolder(treeUri: Uri) {
        contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        preferences.setGameFolder(treeUri)
    }

    /**
     * Scans the game folder and updates the cache: new and changed images are added, images
     * that are gone are dropped. Serials are read afterwards, one game at a time, so the
     * list appears before the slow part. Throws if the folder can't be read.
     */
    suspend fun refresh() = refreshLock.withLock {
        _refreshing.value = true
        try {
            val folder = gameFolder.first()
            if (folder == null) {
                dao.sync(removed = dao.getAll().map { it.uri }, changed = emptyList())
                return@withLock
            }
            val scanned = withContext(Dispatchers.IO) { scan(folder) }
            val cached = dao.getAll().associateBy { it.uri }
            val scannedUris = scanned.mapTo(HashSet()) { it.uri }
            dao.sync(
                removed = cached.keys.filter { it !in scannedUris },
                changed = scanned.mapNotNull { found ->
                    val old = cached[found.uri]
                    when {
                        old == null -> found
                        // A different file under the same name: read the serial again.
                        old.sizeBytes != found.sizeBytes || old.lastModified != found.lastModified ->
                            found
                        else -> null
                    }
                },
            )
            dao.getAll().filter { !it.serialRead }.forEach { readSerial(it.toGame()) }
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * The game's serial (e.g. "SLUS-20312"), or null for images the core can't read it
     * from. Reads it through the core and caches it if that hasn't happened yet.
     */
    suspend fun readSerial(game: Game): String? {
        if (game.serialRead) return game.serial
        val (serial, dbTitle, discSet) = withContext(Dispatchers.IO) {
            core.initialize()
            val serial = core.readGameSerial(game.uri)
            Triple(serial, serial?.let { core.gameDbTitle(it) }, serial?.let { core.discSet(it) })
        }
        dao.setSerial(
            game.uri.toString(), serial, dbTitle, discSet?.serials?.first(), discSet?.name,
            discSet?.serials?.indexOf(serial)?.coerceAtLeast(0) ?: 0,
        )
        return serial
    }

    /**
     * The library's discs that make up the game with [dataSerial] (see [Game.dataSerial]), in disc
     * order; empty for a game the GameDB lists on one disc. Needs the core initialized.
     */
    suspend fun discSet(dataSerial: String): List<Game> = withContext(Dispatchers.IO) {
        val serials = core.discSet(dataSerial)?.serials ?: return@withContext emptyList()
        val bySerial = dao.getAll().filter { it.serial in serials }.associateBy { it.serial }
        serials.mapNotNull { bySerial[it]?.toGame() }
    }

    private val appContext = context.applicationContext

    /**
     * Asks the launcher to pin a shortcut that starts [game] directly, with [icon] (a drawn
     * disc if null). False if the launcher can't pin shortcuts. The shortcut keeps working as long
     * as the game folder's access does.
     */
    fun requestPinnedShortcut(game: Game, serial: String?, icon: Bitmap?): Boolean {
        val manager = appContext.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(appContext, "game:${game.recordKey}")
            .setShortLabel(game.title)
            .setLongLabel(game.title)
            .setIcon(icon?.let(Icon::createWithAdaptiveBitmap) ?: Icon.createWithResource(appContext, R.drawable.ic_shortcut_disc))
            .setIntent(EmulationActivity.shortcutIntent(appContext, game.uri, serial))
            .build()
        return manager.requestPinShortcut(shortcut, null)
    }

    /** Every game image under [treeUri], subfolders included. */
    private fun scan(treeUri: Uri): List<GameEntity> {
        val games = mutableListOf<GameEntity>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.removeFirst())
            val cursor = contentResolver.query(children, PROJECTION, null, null, null)
                ?: throw IllegalStateException("Could not list $children")
            val found = mutableListOf<GameEntity>()
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (it.getString(2) == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (extensionOf(name) in GAME_EXTENSIONS) {
                        found += GameEntity(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            fileName = name,
                            sizeBytes = it.getLong(3),
                            lastModified = it.getLong(4),
                        )
                    }
                }
            }
            // A cue sheet's tracks are not games of their own. Their names need not match the
            // sheet's, so a folder with a cue sheet lists none of its raw images.
            if (found.any { extensionOf(it.fileName) == "cue" }) found.removeAll { extensionOf(it.fileName) in TRACK_EXTENSIONS }
            // A playlist (.m3u) starts its discs itself, so they are not games of their own either.
            val members = found.filter { extensionOf(it.fileName) == "m3u" }.flatMapTo(HashSet()) { playlistMembers(it) }
            if (members.isNotEmpty()) {
                found.removeAll { extensionOf(it.fileName) != "m3u" && it.fileName.lowercase() in members }
            }
            games += found
        }
        return games
    }

    /** Lower-case file names a playlist lists. Names only: the discs are matched within the playlist's folder. */
    private fun playlistMembers(playlist: GameEntity): Set<String> = runCatching {
        contentResolver.openInputStream(Uri.parse(playlist.uri))?.bufferedReader()?.use { reader ->
            reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringAfterLast('/').substringAfterLast('\\').lowercase() }
                .toSet()
        }
    }.getOrNull().orEmpty()

    private fun GameEntity.toGame() = Game(
        uri = Uri.parse(uri),
        fileName = fileName,
        serial = serial,
        serialRead = serialRead,
        sizeBytes = sizeBytes,
        dbTitle = dbTitle,
        groupSerial = groupSerial,
        groupTitle = groupTitle,
        discIndex = discIndex,
        lastModified = lastModified,
    )

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * What the core boots: the disc images Mednafen reads (cue sheets, cdrdao .toc, CloneCD .ccd)
         * and CHD, and playlists (.m3u). A bare .bin or .iso needs a cue sheet beside it.
         */
        val GAME_EXTENSIONS = setOf("cue", "toc", "ccd", "chd", "m3u")

        /** Raw images that are usually a cue sheet's tracks. */
        val TRACK_EXTENSIONS = setOf("bin", "img")

        fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
    }
}
