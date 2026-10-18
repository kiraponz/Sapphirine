package com.kira3.ametrine.emulation.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kira3.ametrine.emucore.EmulatorCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds where the game keeps a number: scan main RAM for a value, let the game change it, scan the
 * survivors again. Values are unsigned and big-endian (the Saturn has its numbers that way round),
 * and sit at offsets into the app's main RAM that are multiples of their size. The addresses it
 * shows are the real Saturn work-RAM addresses ([EmulatorCore.offsetToSaturnAddress]); the offsets
 * stay inside.
 */
class MemorySearch(private val core: EmulatorCore) {
    enum class Mode { Exact, Changed, Unchanged, Increased, Decreased }

    enum class Problem { NoGame, TooMany }

    data class Hit(val address: Int, val value: Long)

    data class State(
        val size: Int = 4,
        /** A first scan has been done. */
        val started: Boolean = false,
        val count: Int = 0,
        /** The candidates, with their current values, once few enough to list. */
        val hits: List<Hit> = emptyList(),
        val busy: Boolean = false,
        val problem: Problem? = null,
    )

    var state by mutableStateOf(State())
        private set

    /** What the value field and the mode chips show; kept here so closing the panel does not lose them. */
    var valueText by mutableStateOf("")
    var mode by mutableStateOf(Mode.Exact)

    // After a first scan for an unknown value there is only the picture of RAM; after any other,
    // the candidate addresses and what they held.
    private var snapshot: ByteArray? = null
    private var addresses = IntArray(0)
    private var values = LongArray(0)

    fun setSize(size: Int) {
        if (!state.started && !state.busy) state = state.copy(size = size)
    }

    fun reset() {
        snapshot = null
        addresses = IntArray(0)
        values = LongArray(0)
        valueText = ""
        mode = Mode.Exact
        state = State(size = state.size)
    }

    /** Starts over: every address holding [value], or with null, every address (to be told apart by how it changes). */
    suspend fun first(value: Long?) {
        val size = state.size
        state = state.copy(busy = true, problem = null)
        val ram = withContext(Dispatchers.IO) { core.readMemory(0, EmulatorCore.MAIN_RAM_BYTES) }
        if (ram == null) {
            state = state.copy(busy = false, problem = Problem.NoGame)
            return
        }
        if (value == null) {
            snapshot = ram
            addresses = IntArray(0)
            values = LongArray(0)
            state = state.copy(started = true, busy = false, count = ram.size / size)
            return
        }
        val found = withContext(Dispatchers.Default) {
            val out = Collector()
            var a = 0
            while (a + size <= ram.size) {
                if (ram.read(a, size) == value && !out.add(a, value)) return@withContext null
                a += size
            }
            out
        }
        if (found == null) {
            state = state.copy(busy = false, problem = Problem.TooMany)
            return
        }
        snapshot = null
        keep(found)
    }

    /** Narrows the candidates to those that now match [mode] ([value] for Exact). */
    suspend fun next(mode: Mode, value: Long?) {
        if (!state.started) return
        val size = state.size
        state = state.copy(busy = true, problem = null)
        val ram = withContext(Dispatchers.IO) { core.readMemory(0, EmulatorCore.MAIN_RAM_BYTES) }
        if (ram == null) {
            state = state.copy(busy = false, problem = Problem.NoGame)
            return
        }
        val before = snapshot
        val found = withContext(Dispatchers.Default) {
            val out = Collector()
            if (before != null) {
                var a = 0
                while (a + size <= ram.size) {
                    val now = ram.read(a, size)
                    if (matches(mode, value, before.read(a, size), now) && !out.add(a, now)) return@withContext null
                    a += size
                }
            } else {
                for (i in addresses.indices) {
                    val now = ram.read(addresses[i], size)
                    if (matches(mode, value, values[i], now) && !out.add(addresses[i], now)) return@withContext null
                }
            }
            out
        }
        if (found == null) {
            state = state.copy(busy = false, problem = Problem.TooMany)
            return
        }
        snapshot = null
        keep(found)
    }

    /** Reads the listed candidates again, for the live view. */
    suspend fun refresh() {
        val shown = state.hits
        if (shown.isEmpty() || state.busy) return
        val size = state.size
        val updated = withContext(Dispatchers.IO) {
            shown.map { hit -> core.readMemory(hit.address, size)?.let { hit.copy(value = it.read(0, size)) } ?: hit }
        }
        if (state.hits.map { it.address } == updated.map { it.address }) state = state.copy(hits = updated)
    }

    /** Writes [value] at each of the listed [targets] and shows it. False if any could not be written. */
    suspend fun write(targets: Collection<Int>, value: Long): Boolean {
        val size = state.size
        val done = withContext(Dispatchers.IO) { targets.filter { core.writeValue(it, size, value) }.toSet() }
        state = state.copy(hits = state.hits.map { if (it.address in done) it.copy(value = value) else it })
        return done.size == targets.size
    }

    private fun keep(found: Collector) {
        addresses = found.addresses.copyOf(found.size)
        values = found.values.copyOf(found.size)
        state = state.copy(
            started = true,
            busy = false,
            count = found.size,
            hits = if (found.size <= MAX_LISTED) List(found.size) { Hit(addresses[it], values[it]) } else emptyList(),
        )
    }

    private fun matches(mode: Mode, wanted: Long?, was: Long, now: Long) = when (mode) {
        Mode.Exact -> now == wanted
        Mode.Changed -> now != was
        Mode.Unchanged -> now == was
        Mode.Increased -> now > was
        Mode.Decreased -> now < was
    }

    private fun ByteArray.read(at: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (this[at + i].toLong() and 0xFF)
        return v
    }

    /** Growing lists of matches; [add] says no once there are too many to be worth keeping. */
    private class Collector {
        var addresses = IntArray(1024)
        var values = LongArray(1024)
        var size = 0

        fun add(address: Int, value: Long): Boolean {
            if (size >= MAX_CANDIDATES) return false
            if (size == addresses.size) {
                addresses = addresses.copyOf(size * 2)
                values = values.copyOf(size * 2)
            }
            addresses[size] = address
            values[size] = value
            size++
            return true
        }
    }

    companion object {
        /** Past this the list is not shown, only counted. */
        const val MAX_LISTED = 50
        private const val MAX_CANDIDATES = 4_000_000
    }
}
