package com.kira3.ametrine.emucore

/** What the core knows about a Saturn BIOS image. */
data class BiosMetadata(
    val region: BiosRegion,
    /** The image's usual file name, e.g. "sega_101.bin", or "Unknown BIOS". */
    val description: String,
)

/** The console regions a BIOS is for: the Saturn has one BIOS for Japan and one for everywhere else. */
enum class BiosRegion {
    Japan,
    NorthAmericaEurope,

    /** An image the core's list does not know. It can still boot. */
    Unknown,
}
