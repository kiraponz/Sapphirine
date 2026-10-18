package com.kira3.ametrine

import android.content.Context
import com.kira3.ametrine.data.AppPreferences
import com.kira3.ametrine.data.BackupRepository
import com.kira3.ametrine.data.GeminiHistoryRepository
import com.kira3.ametrine.data.SaveStateRepository
import com.kira3.ametrine.data.BiosRepository
import com.kira3.ametrine.data.CartRomRepository
import com.kira3.ametrine.data.CheatRepository
import com.kira3.ametrine.data.MacroRepository
import com.kira3.ametrine.data.PadLayoutRepository
import com.kira3.ametrine.data.DriverRepository
import com.kira3.ametrine.data.GameRecordRepository
import com.kira3.ametrine.data.GeminiKeyStore
import com.kira3.ametrine.data.LibraryRepository
import com.kira3.ametrine.data.MemoRepository
import com.kira3.ametrine.data.MemoryCardRepository
import com.kira3.ametrine.data.SettingsRepository
import com.kira3.ametrine.data.db.LibraryDatabase
import com.kira3.ametrine.emucore.EmulatorCore

/**
 * Manual DI: creates the app's long-lived objects once per process. ViewModels get what
 * they need through their factories, never by reaching into this directly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val emulatorCore: EmulatorCore by lazy { EmulatorCore(appContext) }
    val appPreferences: AppPreferences by lazy { AppPreferences(appContext) }
    val biosRepository: BiosRepository by lazy { BiosRepository(appContext, emulatorCore) }
    val cartRomRepository: CartRomRepository by lazy { CartRomRepository(appContext, emulatorCore) }
    private val libraryDatabase: LibraryDatabase by lazy { LibraryDatabase.create(appContext) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(appContext, emulatorCore, appPreferences, libraryDatabase.gameDao(), gameRecordRepository)
    }
    val gameRecordRepository: GameRecordRepository by lazy { GameRecordRepository(appContext) }
    val memoryCardRepository: MemoryCardRepository by lazy { MemoryCardRepository(appContext, emulatorCore) }
    val cheatRepository: CheatRepository by lazy { CheatRepository(appContext) }
    val macroRepository: MacroRepository by lazy { MacroRepository(appContext) }
    val padLayoutRepository: PadLayoutRepository by lazy { PadLayoutRepository(appContext) }
    val memoRepository: MemoRepository by lazy { MemoRepository(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val geminiKeyStore: GeminiKeyStore by lazy { GeminiKeyStore(appContext) }
    val geminiHistoryRepository: GeminiHistoryRepository by lazy { GeminiHistoryRepository(appContext) }
    val saveStateRepository: SaveStateRepository by lazy { SaveStateRepository(appContext, emulatorCore) }
    val backupRepository: BackupRepository by lazy {
        BackupRepository(appContext, emulatorCore, settingsRepository, gameRecordRepository, memoryCardRepository)
    }
    val driverRepository: DriverRepository by lazy { DriverRepository(appContext) }
}
