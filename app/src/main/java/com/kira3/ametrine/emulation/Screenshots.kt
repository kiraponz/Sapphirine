package com.kira3.ametrine.emulation

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile

/**
 * Screenshots of the running game. The core writes the PNG into the app's own folder, which
 * the Files app cannot show, so it is copied into Pictures/Sapphirine/<serial> for the gallery.
 */
class Screenshots(private val context: Context, private val core: EmulatorCore) {
    /** Captures the next frame and publishes it. Returns false if it never arrived or could not be copied. */
    suspend fun capture(serial: String?): Boolean = withContext(Dispatchers.IO) {
        val dir = File(core.dataDir, "snaps").apply { mkdirs() }
        // The core takes the path as is only when it ends in .png.
        val png = File(dir, "${serial ?: "Sapphirine"}_${System.currentTimeMillis()}.png")
        if (!capture(png)) return@withContext false
        runCatching { publish(png, serial) }
            .onFailure { Log.e(TAG, "Could not publish $png", it) }
            .isSuccess
    }

    /** Captures the next frame into the app's cache, not the gallery. The caller deletes the file. Null if it never arrived. */
    suspend fun captureTemporary(): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "temp-shots").apply { mkdirs() }
        File(dir, "${System.currentTimeMillis()}.png").takeIf { capture(it) }
    }

    /** Has the core write the next frame to [png] and waits until the file is complete. */
    private suspend fun capture(png: File): Boolean {
        core.saveScreenshot(png)
        // The core compresses on its own thread, so wait for the file to be complete.
        val written = withTimeoutOrNull(TIMEOUT_MS) {
            while (!isCompletePng(png)) delay(POLL_MS)
        } != null
        if (!written) Log.w(TAG, "Screenshot never arrived: $png")
        return written
    }

    private fun publish(png: File, serial: String?) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, png.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, listOfNotNull(Environment.DIRECTORY_PICTURES, ALBUM, serial).joinToString("/"))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the insert")
        try {
            resolver.openOutputStream(uri)!!.use { out -> png.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        png.delete()
    }

    /** True once [png] ends with its IEND chunk, the last thing the core writes. */
    private fun isCompletePng(png: File): Boolean = runCatching {
        RandomAccessFile(png, "r").use { file ->
            val start = file.length() - PNG_END.size
            start >= 0 && ByteArray(PNG_END.size).also {
                file.seek(start)
                file.readFully(it)
            }.contentEquals(PNG_END)
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Screenshots"
        const val ALBUM = "Sapphirine"
        const val POLL_MS = 100L

        // A large upscaled frame can take a few seconds to compress on a slow phone.
        const val TIMEOUT_MS = 30_000L

        /** The IEND chunk: zero length, its type, and its fixed CRC. */
        val PNG_END = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    }
}
