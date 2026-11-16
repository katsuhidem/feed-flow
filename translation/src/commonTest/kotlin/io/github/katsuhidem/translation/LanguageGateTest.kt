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

    @Test
    fun blocksOfAForeignArticleAreTranslatedUnlessVeryShortOrJapanese() {
        val translate = GateDecision.Translate(null)
        assertEquals(translate, LanguageGate.decideBlock("Bug fixes", FeedLanguage.OTHER))
        assertEquals(translate, LanguageGate.decideBlock("Discussion", null))
        assertEquals(translate, LanguageGate.decideBlock("前言", FeedLanguage.OTHER))
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("nrs", FeedLanguage.OTHER))
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("FAQ", null))
        assertEquals(skip(SkipReason.UNDETERMINED), LanguageGate.decideBlock("1.", FeedLanguage.OTHER))
        assertEquals(skip(SkipReason.JAPANESE_TEXT), LanguageGate.decideBlock("これは日本語です", FeedLanguage.OTHER))
    }

    @Test
    fun codeCountsTowardsTheSizeOfABlock() {
        // "Use `x`": the prose alone is very short, but with its code it is a sentence.
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("Use  ", FeedLanguage.OTHER))
        assertEquals(GateDecision.Translate(null), LanguageGate.decideBlock("Use  ", FeedLanguage.OTHER, codeSpans = 1))
    }

    @Test
    fun inAJapaneseArticleOnlyForeignSentencesAreTranslated() {
        val ja = FeedLanguage.JAPANESE
        // Japanese without kana.
        assertEquals(skip(SkipReason.JAPANESE_TEXT), LanguageGate.decideBlock("同時実行数", ja))
        assertEquals(skip(SkipReason.JAPANESE_TEXT), LanguageGate.decideBlock("CodeRabbit対応", ja))
        // Names and labels.
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("nrs", ja))
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("Discussion", ja))
        assertEquals(skip(SkipReason.TOO_SHORT), LanguageGate.decideBlock("Table of Contents", ja))
        // A quoted English sentence.
        assertEquals(
            GateDecision.Translate(null),
            LanguageGate.decideBlock("Simple things should be simple, complex things should be possible.", ja),
        )
    }

    @Test
    fun articleLanguageIsWeighedByTheSizeOfItsBlocks() {
        val japanese = "大量のプルリクエストをまとめて処理する方法を紹介します。同時に実行する数は設定で変えられます。"
        val english = "This paragraph is written in English and is long enough to count for something in the estimate."
        // Many short lines in Latin letters do not outweigh the Japanese paragraphs.
        val labels = listOf("nrs", "Discussion", "takt merge", "GitHub", "CodeRabbit", "README", "npm", "CLI")
        assertEquals(FeedLanguage.JAPANESE, LanguageGate.estimateArticleLanguage(labels + List(3) { japanese }))
        // An English article that quotes one Japanese sentence.
        assertEquals(FeedLanguage.OTHER, LanguageGate.estimateArticleLanguage(List(6) { english } + "これは日本語の例文です。"))
        // Chinese text has no kana.
        assertEquals(FeedLanguage.OTHER, LanguageGate.estimateArticleLanguage(List(20) { "北京地铁新线路开通，多地迎来强降雨。" }))
        assertEquals(null, LanguageGate.estimateArticleLanguage(listOf("Short note", "お知らせ")))
    }
}
