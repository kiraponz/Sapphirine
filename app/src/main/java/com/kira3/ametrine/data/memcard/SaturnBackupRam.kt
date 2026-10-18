package com.kira3.ametrine.data.memcard

import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.Calendar
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A Saturn backup memory image, as Mednafen keeps it: the console's own 32 KB (`.bkr`, raw) or
 * a backup memory cart's 512 KB (`.bcr`, gzipped), each holding only the bytes the BIOS uses
 * (the odd bytes of the bus). The memory is split into blocks (64 bytes in the console, 512 in
 * the cart); the first two hold the "BackUpRam Format" header.
 *
 * A save starts in a block tagged 0x80000000, which holds its name, language, comment, date and
 * size, then the numbers of its other blocks (ending in 0), then its data. The data and the list
 * run on through those blocks, each tagged 0 and adding what follows its 4-byte tag. All numbers
 * are big-endian. The layout is the BIOS's (Yabause's BiosBUPWrite reads and writes the same), so
 * a save written here loads in the game.
 *
 * Changes stay in memory until [save].
 */
class SaturnBackupRam private constructor(
    private val image: ByteArray,
    private val blockSize: Int,
    private val gzipped: Boolean,
) : SaveStore {

    private val blocks get() = image.size / blockSize

    val state: CardState
        get() = if (image.size >= HEADER.size && HEADER.indices.all { image[it] == HEADER[it] }) CardState.Formatted else CardState.Unsupported

    /** Blocks the saves can use: all but the two of the header. */
    val capacityBlocks: Int get() = blocks - RESERVED_BLOCKS

    override fun list(): List<SaveInfo> = entries().map { entry ->
        SaveInfo(entry.name, comment(entry.first), entry.blocks.size, dateMillis(u32(entry.first * blockSize + 0x1A)))
    }

    override fun read(name: String): SaveData? {
        val entry = entries().firstOrNull { it.name == name } ?: return null
        val start = entry.first * blockSize
        val stream = Stream(entry.blocks)
        stream.skip(2 * entry.blocks.size) // The block list and its 0.
        val data = ByteArray(u32(start + 0x1E))
        for (i in data.indices) data[i] = image[stream.next()]
        return SaveData(
            name = entry.name,
            language = image[start + 0x0F].toInt() and 0xFF,
            comment = image.copyOfRange(start + 0x10, start + 0x1A),
            date = u32(start + 0x1A),
            data = data,
        )
    }

    override fun write(save: SaveData, replace: Boolean): WriteResult {
        if (state != CardState.Formatted) return WriteResult.Failed
        val nameBytes = save.name.toByteArray(Charsets.US_ASCII)
        if (nameBytes.isEmpty() || nameBytes.size > NAME_LENGTH) return WriteResult.Failed
        val existing = entries().firstOrNull { it.name == save.name }
        if (existing != null && !replace) return WriteResult.Exists

        val needed = blocksFor(save.data.size)
        val free = freeBlockList()
        if (free.size + (existing?.blocks?.size ?: 0) < needed) return WriteResult.Full
        existing?.let { delete(it.name) }

        val used = freeBlockList().take(needed)
        for (block in used) image.fill(0, block * blockSize, (block + 1) * blockSize)
        val start = used.first() * blockSize
        putU32(start, TAG_START)
        nameBytes.copyInto(image, start + 0x04)
        image[start + 0x0F] = save.language.toByte()
        save.comment.copyInto(image, start + 0x10, 0, minOf(save.comment.size, COMMENT_LENGTH))
        putU32(start + 0x1A, save.date)
        putU32(start + 0x1E, save.data.size)

        val stream = Stream(used)
        for (block in used.drop(1) + 0) {
            image[stream.next()] = (block shr 8).toByte()
            image[stream.next()] = block.toByte()
        }
        for (b in save.data) image[stream.next()] = b
        return WriteResult.Ok
    }

    /** Clears the save's tag, as the BIOS does: its blocks are free again, their bytes stay. */
    override fun delete(name: String) {
        val entry = entries().firstOrNull { it.name == name } ?: return
        putU32(entry.first * blockSize, 0)
    }

    override fun freeBlocks(): Int = freeBlockList().size

    fun save(file: File) {
        if (gzipped) GZIPOutputStream(file.outputStream()).use { it.write(image) } else file.writeBytes(image)
    }

    private class Entry(val name: String, val first: Int, val blocks: List<Int>)

    /** The byte offsets a save's stream runs through: its first block from 0x22, then each other block after its tag. */
    private inner class Stream(private val chain: List<Int>) {
        private var index = 0
        private var offset = 0x22

        fun next(): Int {
            if (offset == blockSize) {
                index++
                offset = 4
            }
            return chain[index] * blockSize + offset++
        }

        fun skip(count: Int) = repeat(count) { next() }
    }

    private fun entries(): List<Entry> {
        if (state != CardState.Formatted) return emptyList()
        val result = ArrayList<Entry>()
        for (block in RESERVED_BLOCKS until blocks) {
            val start = block * blockSize
            if (image[start].toInt() and 0x80 == 0) continue
            val name = ascii(start + 0x04, NAME_LENGTH)
            val size = u32(start + 0x1E)
            val count = blocksFor(size)
            if (size < 0 || count > capacityBlocks) continue

            // The list names the other blocks, and runs on into them once the first is full; a
            // list that leaves the memory, repeats a block or needs one it has not named yet is
            // not a save.
            val chain = arrayListOf(block)
            val stream = Stream(chain)
            val ok = runCatching {
                while (chain.size < count) {
                    val hi = image[stream.next()].toInt() and 0xFF
                    val lo = image[stream.next()].toInt() and 0xFF
                    val next = (hi shl 8) or lo
                    if (next !in RESERVED_BLOCKS until blocks || next in chain) return@runCatching false
                    chain += next
                }
                true
            }.getOrDefault(false)
            if (!ok) continue
            result += Entry(name, block, chain)
        }
        return result
    }

    /** The blocks a save of [size] bytes takes: the BIOS's count (Yabause's CalcSaveSize). */
    private fun blocksFor(size: Int): Int = 1 + (size + 0x1D) / (blockSize - 6)

    private fun freeBlockList(): List<Int> {
        val used = entries().flatMap { it.blocks }.toSet()
        return (RESERVED_BLOCKS until blocks).filter { it !in used }
    }

    private fun comment(block: Int): String? {
        val start = block * blockSize + 0x10
        var end = start
        while (end < start + COMMENT_LENGTH && image[end].toInt() != 0) end++
        val raw = String(image, start, end - start, charset("Shift_JIS"))
        // Comments are often written in full-width letters; NFKC makes them ordinary ones.
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().ifEmpty { null }
    }

    private fun ascii(at: Int, length: Int): String {
        var end = at
        while (end < at + length && image[end].toInt() != 0) end++
        return String(image, at, end - at, Charsets.US_ASCII)
    }

    private fun u16(at: Int) = ((image[at].toInt() and 0xFF) shl 8) or (image[at + 1].toInt() and 0xFF)

    private fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)

    private fun putU32(at: Int, value: Int) {
        image[at] = (value shr 24).toByte()
        image[at + 1] = (value shr 16).toByte()
        image[at + 2] = (value shr 8).toByte()
        image[at + 3] = value.toByte()
    }

    companion object {
        /** The console's backup memory, as a .bkr file. */
        const val CONSOLE_SIZE = 32 * 1024

        /** A backup memory cart, as a .bcr file once unzipped. */
        const val CART_SIZE = 512 * 1024

        private const val CONSOLE_BLOCK = 0x40
        private const val CART_BLOCK = 0x200
        private const val RESERVED_BLOCKS = 2
        private const val NAME_LENGTH = 11
        private const val COMMENT_LENGTH = 10
        private const val TAG_START = 0x80000000.toInt()

        private val HEADER = "BackUpRam Format".toByteArray(Charsets.US_ASCII)

        /** Reads the backup memory in [file] (a .bkr or a .bcr), or null if it is neither. */
        fun open(file: File): SaturnBackupRam? = runCatching {
            if (file.name.endsWith(".bcr", ignoreCase = true)) {
                val image = GZIPInputStream(file.inputStream()).use { input ->
                    val out = ByteArrayOutputStream(CART_SIZE)
                    input.copyTo(out)
                    out.toByteArray()
                }
                if (image.size == CART_SIZE) SaturnBackupRam(image, CART_BLOCK, gzipped = true) else null
            } else if (file.length() == CONSOLE_SIZE.toLong()) {
                SaturnBackupRam(file.readBytes(), CONSOLE_BLOCK, gzipped = false)
            } else {
                null
            }
        }.getOrNull()

        /** The console's backup memory as the BIOS formats it, with no saves. */
        fun formatted(): SaturnBackupRam = SaturnBackupRam(ByteArray(CONSOLE_SIZE), CONSOLE_BLOCK, gzipped = false).also {
            it.format()
        }

        /** The minutes since 1980 the BIOS dates saves with, as epoch milliseconds in local time; null for 0. */
        private fun dateMillis(minutes: Int): Long? {
            if (minutes <= 0) return null
            return Calendar.getInstance().apply {
                clear()
                set(1980, Calendar.JANUARY, 1)
                add(Calendar.MINUTE, minutes)
            }.timeInMillis
        }
    }

    /** Erases every save, leaving the header (repeated through its first block) and nothing else. */
    fun format() {
        image.fill(0)
        for (i in 0 until blockSize) image[i] = HEADER[i % HEADER.size]
    }
}
