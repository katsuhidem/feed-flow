package io.github.katsuhidem.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageGateTest {
    private fun skip(reason: SkipReason) = GateDecision.Skip(reason)

    @Test
    fun englishTitleIsTranslated() {
        assertEquals(
            GateDecision.Translate(sourceLang = null),
            LanguageGate.decide("Researchers find a faster way to recycle batteries"),
        )
    }

    @Test
    fun japaneseTextIsNotTranslated() {
        assertEquals(skip(SkipReason.JAPANESE_TEXT), LanguageGate.decide("新しい自転車レーンを承認"))
        assertEquals(skip(SkipReason.JAPANESE_TEXT), LanguageGate.decide("Apple、新型デバイスを発表"))
    }

    @Test
    fun kanaLessTitleInAJapaneseFeedIsNotTranslated() {
        assertEquals(
            skip(SkipReason.JAPANESE_FEED),
            LanguageGate.decide("2026年11月期 決算短信", feedLanguage = FeedLanguage.JAPANESE),
        )
    }

    @Test
    fun chineseTitleIsNotMistakenForJapanese() {
        assertEquals(
            GateDecision.Translate(sourceLang = null),
            LanguageGate.decide("苹果发布新款智能家居设备", feedLanguage = FeedLanguage.OTHER),
        )
        // Traditional characters shared with Japanese kanji, still no kana.
        assertEquals(GateDecision.Translate(sourceLang = null), LanguageGate.decide("國產電動汽車出口量創新高"))
    }

    @Test
    fun hanOnlyTitleWithoutFeedEstimateIsTranslated() {
        // Without a feed estimate a kana-less title cannot be told from Chinese; it is translated.
        assertEquals(GateDecision.Translate(sourceLang = null), LanguageGate.decide("決算短信"))
    }

    @Test
    fun languageTagDecidesBeforeTheScript() {
        assertEquals(skip(SkipReason.JAPANESE_TAG), LanguageGate.decide("決算短信", languageTag = "ja-JP"))
        assertEquals(GateDecision.Translate("en"), LanguageGate.decide("Hello world", languageTag = "en-US"))
        assertEquals(GateDecision.Translate("zh-hant"), LanguageGate.decide("國產電動汽車", languageTag = "zh-TW"))
        assertEquals(GateDecision.Translate("zh-hans"), LanguageGate.decide("国产电动汽车", languageTag = "zh"))
    }

    @Test
    fun unknownLanguageTagFallsBackToTheScript() {
        assertEquals(GateDecision.Translate(sourceLang = null), LanguageGate.decide("Saluton mondo", languageTag = "eo"))
    }

    @Test
    fun feedSettingComesFirst() {
        assertEquals(
            skip(SkipReason.FEED_OFF),
            LanguageGate.decide("Hello world", feedMode = FeedTranslationMode.OFF),
        )
        assertEquals(
            GateDecision.Translate(sourceLang = null),
            LanguageGate.decide(
                "2026年11月期 決算短信",
                feedMode = FeedTranslationMode.ON,
                feedLanguage = FeedLanguage.JAPANESE,
            ),
        )
    }

    @Test
    fun textWithAlmostNoLettersIsNotTranslated() {
        assertEquals(skip(SkipReason.UNDETERMINED), LanguageGate.decide("2026-11-14 12:00"))
        assertEquals(skip(SkipReason.UNDETERMINED), LanguageGate.decide("#1"))
        assertEquals(skip(SkipReason.EMPTY), LanguageGate.decide("  "))
        assertEquals(skip(SkipReason.EMPTY), LanguageGate.decide(null))
    }

    @Test
    fun katakanaMiddleDotAndLongVowelMarkAloneAreNotKana() {
        // Both marks also appear in Chinese text.
        assertEquals(GateDecision.Translate(sourceLang = null), LanguageGate.decide("阿尔伯特・爱因斯坦"))
    }

    @Test
    fun feedIsJapaneseWhenMostTitlesHaveKana() {
        val titles = listOf("新機能を公開しました", "障害のお知らせ", "2026年11月期 決算短信", "メンテナンス情報", "採用イベントを開催")
        assertEquals(FeedLanguage.JAPANESE, LanguageGate.estimateFeedLanguage(titles))
    }

    @Test
    fun chineseFeedIsNotJapanese() {
        val titles = listOf("苹果发布新款设备", "北京地铁新线路开通", "多地迎来强降雨", "大学推出免费课程", "博物馆重新开放")
        assertEquals(FeedLanguage.OTHER, LanguageGate.estimateFeedLanguage(titles))
    }

    @Test
    fun englishFeedWithOneJapaneseTitleIsOther() {
        val titles = listOf("Release 1.0", "Release 1.1 is out", "Security advisory", "日本語版を公開", "Roadmap update")
        assertEquals(FeedLanguage.OTHER, LanguageGate.estimateFeedLanguage(titles))
    }

    @Test
    fun tooFewTitlesGiveNoEstimate() {
        assertNull(LanguageGate.estimateFeedLanguage(listOf("新機能を公開しました", "お知らせ")))
        // Titles without letters do not count toward the minimum.
        assertNull(LanguageGate.estimateFeedLanguage(listOf("お知らせ", "障害情報", "更新", "告知", "1.0", "2.0")))
    }
}
