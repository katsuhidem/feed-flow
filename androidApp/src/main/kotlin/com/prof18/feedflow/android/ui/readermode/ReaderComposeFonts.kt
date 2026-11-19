package com.prof18.feedflow.android.ui.readermode

import android.graphics.fonts.SystemFonts
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.prof18.feedflow.core.model.ReaderFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val loadedFamilies = ConcurrentHashMap<ReaderFontFamily, FontFamily>()

/**
 * The bundled reader fonts ship as Java resources of the shared module (the reader page loads them
 * through CSS); Compose needs a file, so each one is copied to the cache once.
 */
@Composable
internal fun rememberReaderFontFamily(family: ReaderFontFamily): FontFamily {
    val cacheDir = LocalContext.current.cacheDir
    val fontFamily by produceState(initialValue = loadedFamilies[family], family) {
        value = loadedFamilies[family] ?: withContext(Dispatchers.IO) {
            loadReaderFontFamily(cacheDir, family)?.also { loadedFamilies[family] = it }
        }
    }
    return fontFamily ?: FontFamily.Default
}

private fun loadReaderFontFamily(cacheDir: File, family: ReaderFontFamily): FontFamily? {
    if (family.isDeviceFont) return deviceFontFile(family)?.let(::variableFontFamily)
    val fileName = family.fileName ?: return FontFamily.Default
    val fontFile = File(cacheDir, "reader-fonts/$fileName")
    if (!fontFile.exists()) {
        val stream = ReaderFontAnchor::class.java.classLoader
            ?.getResourceAsStream("reader-fonts/$fileName") ?: return null
        fontFile.parentFile?.mkdirs()
        val tempFile = File(fontFile.parentFile, "$fileName.tmp")
        stream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
        tempFile.renameTo(fontFile)
    }
    return variableFontFamily(fontFile)
}

// Pinning the variable font's weight axis renders the real weight instead of a synthetic bold.
// fork (Phase 4i): ExtraBold too, the weight of the reader page's headings.
private fun variableFontFamily(fontFile: File): FontFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
            Font(
                file = fontFile,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )

/**
 * fork (Phase 4i): the system file of a font the phone already has. Compose opens the first face of
 * a collection, so the font must be that face (Noto Sans CJK puts Japanese first).
 */
private fun deviceFontFile(family: ReaderFontFamily): File? {
    val name = family.deviceFontName ?: return null
    val listed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        SystemFonts.getAvailableFonts().firstOrNull { font ->
            font.ttcIndex == 0 && font.file?.name?.startsWith("NotoSansCJK") == true &&
                (0 until font.localeList.size()).any { font.localeList[it].language == "ja" }
        }?.file
    } else {
        null
    }
    val file = listed ?: File("/system/fonts/NotoSansCJK-Regular.ttc").takeIf { it.exists() }
    Log.d(TAG, "device font $name: ${file?.name ?: "not found"} (listed=${listed != null})")
    return file
}

private const val TAG = "ReaderFont"

private object ReaderFontAnchor
