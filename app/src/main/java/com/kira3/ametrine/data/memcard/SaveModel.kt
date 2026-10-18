package com.kira3.ametrine.data.memcard

/** A save as the BIOS stores it: its name, language, comment and date, and its data. */
class SaveData(
    val name: String,
    /** 0 Japanese, 1 English, 2 French, 3 German, 4 Spanish, 5 Italian. */
    val language: Int,
    /** 10 bytes of Shift-JIS. */
    val comment: ByteArray,
    /** Minutes since 1980-01-01. */
    val date: Int,
    val data: ByteArray,
)

/** What the list of a backup memory's saves shows. [blocks] is the space it takes there. */
class SaveInfo(
    val name: String,
    /** The game's comment for the save; null if it has none. */
    val title: String?,
    val blocks: Int,
    /** When it was saved, as epoch milliseconds; null if it has no date. */
    val date: Long?,
)

enum class WriteResult { Ok, Exists, Full, Failed }

/** What a backup memory is, as far as this app can read it. */
enum class CardState { Formatted, Unsupported }

/** The saves in a backup memory. */
interface SaveStore {
    fun list(): List<SaveInfo>
    fun read(name: String): SaveData?

    /** Adds [save], replacing a save of the same name only if [replace]. */
    fun write(save: SaveData, replace: Boolean): WriteResult

    fun delete(name: String)

    /** Free space in blocks. */
    fun freeBlocks(): Int
}
