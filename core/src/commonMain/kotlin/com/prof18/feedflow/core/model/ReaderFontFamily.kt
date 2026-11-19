package com.prof18.feedflow.core.model

enum class ReaderFontFamily(
    /** Brand name shown in the font pickers; not translated. Null for [SYSTEM], which uses a localized label. */
    val familyName: String?,
    val cssFamilyName: String?,
    val fileName: String?,
    val italicFileName: String?,
    val cssWeightRange: String?,
    /**
     * fork (Phase 4i): a font the phone already has, named for CSS `local()`. The reader page and the
     * app's own text then draw it from the same system file, with no copy in the APK.
     */
    val deviceFontName: String? = null,
) {
    SYSTEM(null, null, null, null, null),
    LITERATA("Literata", "FeedFlow Literata", "Literata-VF.ttf", "Literata-Italic-VF.ttf", "200 900"),
    SOURCE_SERIF_4(
        "Source Serif 4",
        "FeedFlow Source Serif 4",
        "SourceSerif4-VF.ttf",
        "SourceSerif4-Italic-VF.ttf",
        "200 900",
    ),
    IBM_PLEX_SANS(
        "IBM Plex Sans",
        "FeedFlow IBM Plex Sans",
        "IBMPlexSans-VF.ttf",
        "IBMPlexSans-Italic-VF.ttf",
        "100 700",
    ),
    ATKINSON_HYPERLEGIBLE_NEXT(
        "Atkinson Hyperlegible Next",
        "FeedFlow Atkinson Hyperlegible Next",
        "AtkinsonHyperlegibleNext-VF.ttf",
        "AtkinsonHyperlegibleNext-Italic-VF.ttf",
        "200 800",
    ),

    // fork (Phase 4i): Samsung's default Japanese font has no bold, so the page and the app bolden it
    // in different ways. Noto Sans CJK JP is on Android phones as a variable font (weights 400 to 900).
    NOTO_SANS_CJK_JP(
        "Noto Sans CJK JP",
        "FeedFlow Noto Sans CJK JP",
        null,
        null,
        "400 900",
        deviceFontName = "NotoSansCJKJP-Regular",
    ),
    ;

    val isBundled: Boolean
        get() = fileName != null

    val isDeviceFont: Boolean
        get() = deviceFontName != null

    /** The bundled files lack Greek and Cyrillic, so those articles fall back to the system font per glyph. */
    val isLatinScriptOnly: Boolean
        get() = this == ATKINSON_HYPERLEGIBLE_NEXT

    /** Value for a CSS `font-family` property; bundled fonts fall back to the system stack per glyph. */
    val cssFontFamilyValue: String
        get() = cssFamilyName?.let { "'$it', $SYSTEM_FONT_STACK" } ?: SYSTEM_FONT_STACK

    companion object {
        const val SYSTEM_FONT_STACK: String =
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, " +
                "'Open Sans', 'Helvetica Neue', sans-serif"

        fun fromStorageName(name: String?): ReaderFontFamily =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
