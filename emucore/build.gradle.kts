plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The emulation core. The native part builds Mednafen's ss module (Saturn) from the Mednafen
// 1.32.1 checkout in external/sapphirine-core into libemucore.so (see AGENTS.md); NativeCore.kt
// stays the Kotlin stub for now and is wired up to the native library later.

val coreDir = rootProject.file("external/sapphirine-core")

fun coreArgs(buildType: String) = listOf(
    "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=$buildType",
    "-DCORE_DIR=$coreDir",
)

android {
    namespace = "com.kira3.ametrine.emucore"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            externalNativeBuild { cmake { arguments += coreArgs("Release") } }
        }
        debug {
            // Optimized: an unoptimized core would be far too slow to test.
            externalNativeBuild { cmake { arguments += coreArgs("RelWithDebInfo") } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Fail early with the fix, rather than deep inside CMake.
tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    // Local, so the action doesn't capture the build script (the configuration cache can't store it).
    val coreDir = coreDir
    doFirst {
        check(File(coreDir, "src/ss").isDirectory) {
            "external/sapphirine-core/src/ss is missing: the Mednafen core checkout is not there " +
                "(expected at $coreDir)"
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
