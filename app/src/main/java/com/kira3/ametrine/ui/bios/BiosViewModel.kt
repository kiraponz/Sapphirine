package com.kira3.ametrine.ui.bios

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kira3.ametrine.AmetrineApplication
import com.kira3.ametrine.data.Bios
import com.kira3.ametrine.data.BiosRepository
import com.kira3.ametrine.data.CartRom
import com.kira3.ametrine.data.CartRomRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BiosUiState(
    /** Null while the BIOS folder is being read. */
    val installed: List<Bios>? = null,
    val importing: Boolean = false,
    val invalidFile: Boolean = false,
    /** The ROM carts that have a file. */
    val carts: Set<CartRom> = emptySet(),
    val invalidCart: Boolean = false,
)

class BiosViewModel(
    private val repository: BiosRepository,
    private val cartRepository: CartRomRepository,
) : ViewModel() {
    private val installed = MutableStateFlow<List<Bios>?>(null)
    private val importing = MutableStateFlow(false)
    private val invalidFile = MutableStateFlow(false)
    private val carts = MutableStateFlow<Set<CartRom>>(emptySet())
    private val invalidCart = MutableStateFlow(false)

    val uiState: StateFlow<BiosUiState> = combine(installed, importing, invalidFile, carts, invalidCart) { installed, importing, invalid, carts, invalidCart ->
        BiosUiState(installed, importing, invalid, carts, invalidCart)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BiosUiState())

    init {
        reload()
    }

    fun onBiosPicked(uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            val bios = runCatching { repository.import(uri) }
                .onFailure { Log.e(TAG, "Importing BIOS failed", it) }
                .getOrNull()
            importing.value = false
            if (bios == null) invalidFile.value = true else reload()
        }
    }

    fun delete(bios: Bios) {
        viewModelScope.launch {
            runCatching { repository.delete(bios.fileName) }
                .onFailure { Log.e(TAG, "Deleting ${bios.fileName} failed", it) }
            reload()
        }
    }

    fun onInvalidFileShown() = invalidFile.update { false }

    fun onCartPicked(rom: CartRom, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching { cartRepository.import(rom, uri) }
                .onFailure { Log.e(TAG, "Importing ${rom.fileName} failed", it) }
                .getOrDefault(false)
            if (!ok) invalidCart.value = true
            carts.value = cartRepository.installed()
        }
    }

    fun deleteCart(rom: CartRom) {
        viewModelScope.launch {
            runCatching { cartRepository.delete(rom) }
                .onFailure { Log.e(TAG, "Deleting ${rom.fileName} failed", it) }
            carts.value = cartRepository.installed()
        }
    }

    fun onInvalidCartShown() = invalidCart.update { false }

    private fun reload() {
        viewModelScope.launch {
            carts.value = runCatching { cartRepository.installed() }.getOrDefault(emptySet())
            installed.value = runCatching { repository.installed() }
                .onFailure { Log.e(TAG, "Listing BIOS files failed", it) }
                .getOrDefault(emptyList())
        }
    }

    companion object {
        private const val TAG = "BiosViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AmetrineApplication).container
                BiosViewModel(container.biosRepository, container.cartRomRepository)
            }
        }
    }
}
