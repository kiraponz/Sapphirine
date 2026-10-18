package com.kira3.ametrine.data

import android.content.Context
import android.net.Uri
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A ROM cartridge a few games need. The core finds it by [fileName] in the carts folder. */
enum class CartRom(val fileName: String) {
    KingOfFighters95("kof95.bin"),
    Ultraman("ultraman.bin"),
}

/**
 * The ROM carts in [EmulatorCore.cartsDir]. The core opens them with plain file APIs, so a file
 * picked through SAF is copied in there. Only the size is checked: the dumps are 2 MiB.
 */
class CartRomRepository(
    context: Context,
    private val core: EmulatorCore,
) {
    private val contentResolver = context.applicationContext.contentResolver

    /** The carts that have a file. */
    suspend fun installed(): Set<CartRom> = withContext(Dispatchers.IO) {
        CartRom.entries.filterTo(HashSet()) { File(core.cartsDir, it.fileName).isFile }
    }

    /** Copies the ROM at [uri] in as [rom]. False when it is no 2 MiB image or cannot be read. */
    suspend fun import(rom: CartRom, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        core.cartsDir.mkdirs()
        val temp = File(core.cartsDir, "${rom.fileName}.tmp")
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || !isValidSize(temp.length()) || !temp.renameTo(File(core.cartsDir, rom.fileName))) {
            temp.delete()
            return@withContext false
        }
        true
    }

    suspend fun delete(rom: CartRom) = withContext(Dispatchers.IO) {
        File(core.cartsDir, rom.fileName).delete()
        Unit
    }

    companion object {
        const val ROM_BYTES = 0x200000L

        fun isValidSize(bytes: Long): Boolean = bytes == ROM_BYTES
    }
}
