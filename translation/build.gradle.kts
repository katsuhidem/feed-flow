// fork: on-device translation (Phase 2). See docs/PLAN.md "Phase 2 タイトル翻訳".
plugins {
    alias(libs.plugins.feedflow.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "io.github.katsuhidem.translation"
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        androidMain {
            dependencies {
                implementation(project(":llama"))
                implementation(libs.sqldelight.android.driver)
                implementation(libs.touchlab.kermit)
                implementation("com.squareup.okhttp3:okhttp:5.5.1")
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

// A separate database file: adding tables to the upstream schema would
// collide with upstream migration numbers.
sqldelight {
    databases {
        create("TranslationDB") {
            packageName.set("io.github.katsuhidem.translation.db")
        }
    }
}
