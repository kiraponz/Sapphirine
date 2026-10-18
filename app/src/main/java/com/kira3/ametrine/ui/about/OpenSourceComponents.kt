package com.kira3.ametrine.ui.about

import android.content.Context
import androidx.annotation.StringRes
import com.kira3.ametrine.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Something the APK contains that someone else wrote, with the license it is used under.
 *
 * [files] are under `assets/licenses/`. A core usually brings its own texts (Aegirine copies
 * them from the core's tree at build time); the app keeps the plain license texts for parts whose
 * tree has none. [notice] is the copyright line for those, shown above the text.
 */
data class OpenSourceComponent(
    val id: String,
    val name: String,
    val license: String,
    val url: String,
    val files: List<String>,
    val notice: String? = null,
)

enum class ComponentGroup(@param:StringRes val title: Int) {
    Core(R.string.licenses_group_core),
    Native(R.string.licenses_group_native),
    Assets(R.string.licenses_group_assets),
    Android(R.string.licenses_group_android),
}

/**
 * Everything the licenses screen lists, in the order it lists them: the core, what it links, and
 * the Android libraries.
 */
val openSourceComponents: Map<ComponentGroup, List<OpenSourceComponent>> = mapOf(
    ComponentGroup.Core to listOf(
        OpenSourceComponent(
            "mednafen", "Mednafen (Sega Saturn core)", "GPL-2.0-or-later", "https://mednafen.github.io",
            listOf("gpl-2.0.txt"),
            notice = "Copyright the Mednafen Team. Built from the fork at $CORE_SOURCE_URL, with Sapphirine's changes to disc loading and state access.",
        ),
    ),
    ComponentGroup.Native to listOf(
        OpenSourceComponent(
            "libchdr", "libchdr", "BSD-3-Clause", "https://github.com/rtissera/libchdr",
            listOf("libchdr-bsd-3-clause.txt"),
        ),
        OpenSourceComponent(
            "lzma", "LZMA SDK", "Public domain", "https://www.7-zip.org/sdk.html",
            listOf("lzma-sdk.txt"), notice = "Igor Pavlov",
        ),
        OpenSourceComponent(
            "zstd", "Zstandard (decoder)", "BSD-3-Clause", "https://github.com/facebook/zstd",
            listOf("zstd-bsd.txt"),
        ),
        OpenSourceComponent(
            "tremor", "Tremor (Ogg Vorbis decoder)", "BSD-3-Clause", "https://gitlab.xiph.org/xiph/tremor",
            listOf("tremor-bsd.txt"),
        ),
        OpenSourceComponent(
            "musepack", "Musepack decoder", "BSD-3-Clause", "https://www.musepack.net",
            listOf("musepack-bsd-3-clause.txt"),
        ),
        OpenSourceComponent(
            "trio", "trio", "ISC-style", "https://daniel.haxx.se/projects/trio/",
            listOf("trio.txt"),
        ),
        OpenSourceComponent(
            "minilzo", "miniLZO", "GPL-2.0-or-later", "https://www.oberhumer.com/opensource/lzo/",
            listOf("gpl-2.0.txt"), notice = "Copyright Markus F.X.J. Oberhumer",
        ),
        OpenSourceComponent(
            "quicklz", "QuickLZ 1.3.1", "GPL-2.0", "http://www.quicklz.com",
            listOf("gpl-2.0.txt"), notice = "Copyright Lasse Mikkel Reinhold",
        ),
    ),
    ComponentGroup.Android to listOf(
        OpenSourceComponent(
            "androidx", "AndroidX and Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/androidx",
            listOf("apache-2.0.txt"), notice = "Copyright The Android Open Source Project",
        ),
        OpenSourceComponent(
            "kotlin", "Kotlin, kotlinx.coroutines and kotlinx.serialization", "Apache-2.0", "https://kotlinlang.org",
            listOf("apache-2.0.txt"), notice = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
        ),
    ),
)

/** Where this app's source is published. */
const val SOURCE_URL = "https://github.com/kiraponz/Sapphirine"

/** Where the Mednafen fork the core is built from is published (see [coreSourceUrl]). */
const val CORE_SOURCE_URL = "https://github.com/kiraponz/sapphirine-core"

/** The core's source at [commit], kept so the core's source stays available. Null for an unknown commit. */
fun coreSourceUrl(commit: String): String? = "$CORE_SOURCE_URL/tree/$commit".takeIf { commit != "unknown" }

/** This app itself, for its own license from About. */
val ametrineComponent = OpenSourceComponent(
    "sapphirine", "Sapphirine", "GPL-3.0", SOURCE_URL, listOf("gpl-3.0.txt"),
    notice = "Copyright 2026 Kira",
)

fun openSourceComponent(id: String): OpenSourceComponent? =
    if (id == ametrineComponent.id) ametrineComponent
    else openSourceComponents.values.firstNotNullOfOrNull { group -> group.firstOrNull { it.id == id } }

/** The component's license texts, one after another. */
suspend fun licenseText(context: Context, component: OpenSourceComponent): String = withContext(Dispatchers.IO) {
    component.files.joinToString("\n\n") { name ->
        context.assets.open("licenses/$name").bufferedReader().use { it.readText() }.trimEnd()
    }
}
