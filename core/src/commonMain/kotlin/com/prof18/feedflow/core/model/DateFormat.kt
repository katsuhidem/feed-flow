package com.prof18.feedflow.core.model

enum class DateFormat {
    // DD/MM
    NORMAL,

    // MM/DD
    AMERICAN,

    // YYYY-MM-DD
    ISO,

    // DD.MM
    DAY_MONTH_DOT,

    // fork (Phase 4g): M/D this year, YYYY/M/D before, a space before the time
    JAPANESE,
}
