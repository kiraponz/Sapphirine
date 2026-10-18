package com.kira3.ametrine.data.memcard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.GZIPOutputStream

class SaturnBackupRamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun formattedMemoryIsEmpty() {
        val ram = SaturnBackupRam.formatted()
        assertEquals(CardState.Formatted, ram.state)
        assertEquals(emptyList<SaveInfo>(), ram.list())
        assertEquals(510, ram.freeBlocks())
        assertEquals(510, ram.capacityBlocks)
    }

    @Test
    fun savesRoundTripThroughAFile() {
        val ram = SaturnBackupRam.formatted()
        // 1000 bytes: the block list runs past the first block, which holds only 15 entries.
        val save = save("GAME_01", size = 1000, comment = "ＳＡＶＥ")
        assertEquals(WriteResult.Ok, ram.write(save, replace = false))

        val file = temp.newFile("game.bkr")
        ram.save(file)
        assertEquals(SaturnBackupRam.CONSOLE_SIZE.toLong(), file.length())
        val reopened = SaturnBackupRam.open(file)!!
        val info = reopened.list().single()
        assertEquals("GAME_01", info.name)
        assertEquals("SAVE", info.title)
        // 1 + (1000 + 0x1D) / 58: the BIOS's count.
        assertEquals(18, info.blocks)
        assertEquals(510 - 18, reopened.freeBlocks())

        val read = reopened.read("GAME_01")!!
        assertArrayEquals(save.data, read.data)
        assertArrayEquals(save.comment, read.comment)
        assertEquals(save.date, read.date)
        assertEquals(1, read.language)
    }

    @Test
    fun smallSaveFitsOneBlock() {
        val ram = SaturnBackupRam.formatted()
        ram.write(save("TINY", size = 28), replace = false)
        assertEquals(1, ram.list().single().blocks)
        assertEquals(28, ram.read("TINY")!!.data.size)
    }

    @Test
    fun deleteFreesTheBlocks() {
        val ram = SaturnBackupRam.formatted()
        ram.write(save("A", size = 300), replace = false)
        ram.write(save("B", size = 300), replace = false)
        ram.delete("A")
        assertEquals(listOf("B"), ram.list().map { it.name })
        assertNull(ram.read("A"))
        assertEquals(510 - 6, ram.freeBlocks())
        // The freed blocks take a new save.
        assertEquals(WriteResult.Ok, ram.write(save("C", size = 300), replace = false))
        assertArrayEquals(save("C", size = 300).data, ram.read("C")!!.data)
        assertArrayEquals(save("B", size = 300).data, ram.read("B")!!.data)
    }

    @Test
    fun writeChecksNameAndRoom() {
        val ram = SaturnBackupRam.formatted()
        ram.write(save("A", size = 10), replace = false)
        assertEquals(WriteResult.Exists, ram.write(save("A", size = 10), replace = false))
        assertEquals(WriteResult.Ok, ram.write(save("A", size = 20), replace = true))
        assertEquals(20, ram.read("A")!!.data.size)
        assertEquals(WriteResult.Full, ram.write(save("BIG", size = 40000), replace = false))
        assertEquals(WriteResult.Failed, ram.write(save("TWELVE_CHARS", size = 10), replace = false))
    }

    @Test
    fun readsACartFile() {
        val image = ByteArray(SaturnBackupRam.CART_SIZE)
        for (i in 0 until 0x200) image[i] = "BackUpRam Format"[i % 16].code.toByte()
        val file = temp.newFile("game.bcr")
        GZIPOutputStream(file.outputStream()).use { it.write(image) }

        val cart = SaturnBackupRam.open(file)!!
        assertEquals(CardState.Formatted, cart.state)
        assertEquals(1022, cart.capacityBlocks)
        cart.write(save("CART", size = 5000), replace = false)
        cart.save(file)
        assertArrayEquals(save("CART", size = 5000).data, SaturnBackupRam.open(file)!!.read("CART")!!.data)
    }

    @Test
    fun otherFilesAreNotBackupMemory() {
        val file = temp.newFile("other.bkr").apply { writeBytes(ByteArray(1000)) }
        assertNull(SaturnBackupRam.open(file))
        val unformatted = temp.newFile("blank.bkr").apply { writeBytes(ByteArray(SaturnBackupRam.CONSOLE_SIZE)) }
        assertEquals(CardState.Unsupported, SaturnBackupRam.open(unformatted)!!.state)
    }

    private fun save(name: String, size: Int, comment: String = "") = SaveData(
        name = name,
        language = 1,
        comment = comment.toByteArray(charset("Shift_JIS")).copyOf(10),
        date = 23_000_000,
        data = ByteArray(size) { (it * 7 + name.length).toByte() },
    )
}
