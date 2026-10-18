package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kira3.ametrine.emucore.BiosMetadata
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class Bios(val fileName: String, val metadata: BiosMetadata)

/**
 * The BIOS files in [EmulatorCore.biosDir]. The core opens the BIOS with plain file APIs, so a
 * file picked through SAF is copied in there.
 *
 * Nothing is selected here: for each game the core picks a BIOS for the game's region from the
 * folder (preferring the console's own), unless the game's settings name one.
 */
class BiosRepository(
    context: Context,
    private val core: EmulatorCore,
) {
    private val contentResolver = context.applicationContext.contentResolver
    private val changes = MutableStateFlow(0)

    /** An installed BIOS, or null while there is none and no game can start. */
    val available: Flow<Bios?> = changes.map { installed().firstOrNull() }

    /** Copies the BIOS at [uri] into the BIOS folder. Returns null if the file is not a Saturn BIOS. */
    suspend fun import(uri: Uri): Bios? = withContext(Dispatchers.IO) {
        core.initialize()
        val metadata = core.readBiosMetadata(uri) ?: return@withContext null
        val name = displayName(uri)?.let(::sanitizeFileName) ?: "bios.bin"
        val target = File(core.biosDir, name)
        val temp = File(core.biosDir, "$name.tmp")
        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: return@withContext null
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withContext null
        }
        changes.value++
        Bios(name, metadata)
    }

    /** Deletes the BIOS file. Games set to use it go back to the one the core picks. */
    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(core.biosDir, fileName).delete()
        changes.value++
    }

    /** Every valid BIOS in the BIOS folder, by region and then file name. */
    suspend fun installed(): List<Bios> = withContext(Dispatchers.IO) {
        core.biosDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .mapNotNull { readInstalled(it.name) }
            .sortedWith(compareBy<Bios> { it.metadata.region }.thenBy { it.fileName.lowercase() })
    }

    private suspend fun readInstalled(name: String): Bios? {
        val file = File(core.biosDir, name)
        if (!file.isFile) return null
        core.initialize()
        val metadata = core.readBiosMetadata(Uri.fromFile(file)) ?: return null
        return Bios(name, metadata)
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun sanitizeFileName(name: String): String? =
        name.replace('/', '_').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
