package io.github.katsuhidem.translation

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.katsuhidem.translation.db.TranslationDB
import kotlinx.coroutines.Dispatchers
import java.io.File

/** The translation cache on its own database file ("translation.db"), apart from the app's database. */
fun createTranslationCache(context: Context): TranslationCache {
    val driver = AndroidSqliteDriver(TranslationDB.Schema, context.applicationContext, "translation.db")
    return TranslationCache(TranslationDB(driver), System::currentTimeMillis, Dispatchers.IO)
}

/** Model files go to the no-backup directory: large, and downloadable again. */
fun createModelStore(context: Context): ModelStore =
    ModelStore(File(context.applicationContext.noBackupFilesDir, "models"))
