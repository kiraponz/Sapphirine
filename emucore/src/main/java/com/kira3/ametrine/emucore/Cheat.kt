package com.kira3.ametrine.emucore

import kotlinx.serialization.Serializable

/** One cheat of a game: a name and the code lines the core runs while it is [enabled]. */
@Serializable
data class Cheat(
    val id: String,
    val name: String,
    /** Action Replay lines ("160AB9BE FFFF"), one per line. */
    val code: String,
    val enabled: Boolean = false,
)

/** What [ActionReplay.check] found in a cheat's code. */
class CodeCheck(
    /** The code lines, as "AAAAAAAA VVVV". */
    val lines: List<String>,
    /** The 1-based line the core wouldn't understand, or null when every line is fine. */
    val badLine: Int?,
)

/**
 * Reads the Action Replay codes the core runs for a Saturn game. The first hex digit of a line is
 * the command: 1 writes the value (16 bits) at the address, 3 writes its value's low byte (8 bits),
 * and D runs the next line only when the 16-bit value at the address equals the value. The address a
 * command acts on is the line's other seven digits, as 0x0AAAAAAA.
 */
object ActionReplay {
    /** An 8-digit command-and-address, then a 4-digit value ("160AB9BE FFFF"). */
    private val codeLine = Regex("""^([0-9A-Fa-f]{8})[\s:]+([0-9A-Fa-f]{4})$""")

    private val lowWorkRam = 0x00200000..0x002FFFFF

    /** The High work RAM is 1 MB, but its map echoes it over 0x06000000..0x07FFFFFF. */
    private val highWorkRam = 0x06000000..0x07FFFFFF

    /** Reads [code] line by line; blank lines and comments (`#`, `;`, `//`) are skipped. */
    fun check(code: String): CodeCheck {
        val lines = ArrayList<String>()
        var bad: Int? = null

        // The last D line, while nothing valid has come after it: the line after a D one is what the
        // D conditions, so a D with nothing under it guards nothing (the core would drop it).
        var hangingCondition: Int? = null

        code.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//") -> Unit
                else -> {
                    val match = codeLine.matchEntire(line)
                    if (match != null) {
                        // The command is the first digit; it acts at the other seven digits, as
                        // 0x0AAAAAAA (the core masks the line's digits the same way).
                        val command = match.groupValues[1].firstOrNull()?.digitToIntOrNull(16)
                        val address = match.groupValues[1].drop(1).toIntOrNull(16)
                        val value = match.groupValues[2].toIntOrNull(16)
                        if (command != null && address != null && value != null &&
                            accepts(command, address, value)) {
                            lines += "${match.groupValues[1].uppercase()} ${match.groupValues[2].uppercase()}"
                            hangingCondition = if (command == 0xD) index + 1 else null
                        } else if (bad == null) {
                            bad = index + 1
                        }
                    } else if (bad == null) {
                        bad = index + 1
                    }
                }
            }
        }

        return CodeCheck(lines, bad ?: hangingCondition)
    }

    /** True when the core runs a line of [command] at this [address] (0x0AAAAAAA) with [value]. */
    private fun accepts(command: Int, address: Int, value: Int): Boolean {
        val inWorkRam = address in lowWorkRam || address in highWorkRam
        return when (command) {
            0x1, 0xD -> inWorkRam
            0x3 -> inWorkRam && value <= 0xFF
            else -> false
        }
    }
}
