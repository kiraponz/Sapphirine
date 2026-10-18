package com.kira3.ametrine.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Only a cache of what the game folder holds, so a schema change just rebuilds it. Version 4
 * drops the serials the stub core made up, so the Saturn core reads them again.
 */
@Database(entities = [GameEntity::class], version = 4, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "library.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
