// fork: Tailscale integration (Phase 1). See docs/PLAN.md "Phase 1 Tailscale統合".
plugins {
    alias(libs.plugins.feedflow.library)
}

kotlin {
    android {
        namespace = "io.github.katsuhidem.tailnet"
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                // tsbridge is linked by :androidApp (implementation); here only for compiling.
                compileOnly(files("libs/tsbridge-0.1.0.aar"))
                implementation("com.squareup.okhttp3:okhttp:5.5.1")
                implementation(libs.androidx.lifecycle.process)
                implementation(libs.touchlab.kermit)
            }
        }
    }
}
