package com.prof18.feedflow.shared.domain.readerfont

import com.prof18.feedflow.core.model.ReaderFontFamily
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Bundled font file bytes from the `reader-fonts/` resources, or null when the file is missing. */
internal expect fun readReaderFontBytes(fileName: String): ByteArray?

private val fontFaceCssByFamily: Map<ReaderFontFamily, Lazy<String>> =
    ReaderFontFamily.entries.associateWith { family -> lazy { buildFontFaceCss(family) } }

fun readerFontFaceCss(vararg families: ReaderFontFamily): String =
    families.filter { it.isBundled || it.isDeviceFont }.distinct()
        .joinToString("\n") { fontFaceCssByFamily.getValue(it).value }

private fun buildFontFaceCss(family: ReaderFontFamily): String = listOfNotNull(
    family.deviceFontName?.let { deviceFontFace(family, it) },
    family.fileName?.let { fontFace(family, it, "normal") },
    family.italicFileName?.let { fontFace(family, it, "italic") },
).joinToString("\n")

// fork (Phase 4i): the font comes from the phone's own files, by its PostScript name.
private fun deviceFontFace(family: ReaderFontFamily, name: String): String = """
    @font-face {
        font-family: '${family.cssFamilyName}';
        src: local('$name');
        font-weight: ${family.cssWeightRange};
        font-style: normal;
    }
""".trimIndent()

private fun fontFace(family: ReaderFontFamily, fileName: String, style: String): String? {
    val bytes = readReaderFontBytes(fileName) ?: return null
    return """
        @font-face {
            font-family: '${family.cssFamilyName}';
            src: url('${bytes.toFontDataUri()}') format('truetype');
            font-weight: ${family.cssWeightRange};
            font-style: $style;
            font-display: swap;
        }
    """.trimIndent()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toFontDataUri(): String =
    "data:font/ttf;base64,${Base64.Default.encode(this)}"
