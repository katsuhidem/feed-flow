// fork: llama.cpp with a thin JNI layer (Phase 2). A plain Android library,
// because the KMP Android library plugin has no CMake support.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.katsuhidem.llama"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Only the device ABI: each ABI adds minutes to the build and megabytes to the APK.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Release in every variant: a debug build of llama.cpp is several times slower.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
