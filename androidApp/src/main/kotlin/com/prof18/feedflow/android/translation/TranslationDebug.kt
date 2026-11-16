package com.prof18.feedflow.android.translation

import io.github.katsuhidem.translation.TokenBudget
import io.github.katsuhidem.translation.TranslationEngine
import io.github.katsuhidem.translation.TranslationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * fork (Phase 3): aids for checking article translation on the device.
 * Only debug builds use them (switches at the end of the translation settings).
 *
 * - A built-in article with the shapes that the feeds on the device do not
 *   have: quotes, nested lists, text laid out with line breaks only, a
 *   table, the same code twice, a code block, a Japanese paragraph, long
 *   paragraphs.
 * - [FaultInjectingEngine] makes three marked paragraphs of that article
 *   fail, each in its own way.
 *
 * The markers sit more than 200 characters into their paragraphs: the start
 * of a paragraph is passed along as context for the next one, and a marker
 * there would make the next paragraph fail too.
 */
internal object TranslationDebug {
    const val ARTICLE_ID = "debug-translation-check"
    const val TITLE = "Translation check article"

    /** The piece of text with this marker stops at the output budget (recorded as "truncated"). */
    const val TRUNCATE_MARKER = "zebra crossing"

    /** The translation of the text with this marker loses its code placeholders (recorded as "code_check"). */
    const val CODE_LOSS_MARKER = "mango package"

    /** The text with this marker counts as too many tokens (recorded as "over_budget"). */
    const val OVER_BUDGET_MARKER = "pineapple ledger"

    private val longPlain = (1..40).joinToString(" ") { "Sentence number $it of the long paragraph stands here for the check." } +
        " The walk ends at the $TRUNCATE_MARKER."

    private val longWithCode = (1..40).joinToString(" ") {
        when (it) {
            5 -> "Step $it: run <code>git status</code> and read what it prints."
            20 -> "Step $it: run <code>git stash</code> to put the changes aside."
            38 -> "Step $it: run <code>git status</code> once more to see that the tree is clean."
            else -> "Step $it: check the output before going on to the next step."
        }
    }

    private val noSentenceEnds = "The list goes on without a full stop: " +
        (1..110).joinToString(", ") { "entry number $it" }

    val ARTICLE_HTML: String = """
<p>This article is built into the debug build to check how different kinds of blocks are translated. Each section names what it checks.</p>
<h2>Quotes</h2>
<blockquote><p>The first paragraph inside a quote should get one translation.</p><p>The second paragraph inside the same quote should get its own translation.</p></blockquote>
<blockquote>A quote that holds its text directly, without a paragraph element, keeps the translation inside the quote.</blockquote>
<h2>Lists</h2>
<ul><li>A parent item with its own sentence before the children.<p>A child paragraph inside the parent item.</p><ul><li>A nested item under the parent.</li></ul>A second sentence of the parent after the children.</li><li>A plain item on its own.</li></ul>
<h2>Line breaks only</h2>
<div>This part of the article is built from a single container and line breaks.<br><br>Two line breaks in a row start a new paragraph like this one.<br>A single line break stays inside the same paragraph.</div>
<h2>Table</h2>
<table><tr><th>Setting name</th><th>What it changes</th></tr><tr><td>Refresh interval</td><td>How often the feeds are fetched in the background.</td></tr></table>
<h2>Code</h2>
<p>Run <code>npm install</code> first, then run <code>npm install</code> again with <code>--force</code> if the first attempt fails.</p>
<pre><code>npm install --force
echo "this block must stay untranslated"</code></pre>
<h2>Japanese</h2>
<p>この段落は日本語なので翻訳されません。</p>
<h2>Long paragraph in three pieces</h2>
<p>$longPlain</p>
<h2>Long paragraph with code</h2>
<p>$longWithCode</p>
<h2>Long text without sentence ends</h2>
<p>$noSentenceEnds</p>
<h2>Code that the translation loses</h2>
<p>Some packages need an extra step before they can be used, and this paragraph is long on purpose so that the part that matters comes late in the text, well after the first two hundred characters of the paragraph have gone by. Install the $CODE_LOSS_MARKER with <code>pip install mango</code> before running the tool.</p>
<h2>Input over the limit</h2>
<p>Some inputs are too large for the model to take in one go, and this paragraph is long on purpose so that the part that matters comes late in the text, well after the first two hundred characters of the paragraph have gone by. The $OVER_BUDGET_MARKER is the example that stands for such an input here.</p>
<h2>The end</h2>
<p>This last paragraph should be translated in every run.</p>
"""
}

/**
 * Makes the marked paragraphs of the check article fail while [active]
 * returns true; everything else goes to [delegate] untouched. The failures
 * take the same paths as real ones: the queue sees a text that does not fit,
 * a generation that stopped at the budget, or a translation without its
 * placeholders.
 */
internal class FaultInjectingEngine(
    private val delegate: TranslationEngine,
    private val active: () -> Boolean,
) : TranslationEngine by delegate {
    override suspend fun countTokens(text: String): Int =
        if (active() && text.contains(TranslationDebug.OVER_BUDGET_MARKER)) TokenBudget.N_CTX else delegate.countTokens(text)

    override fun translate(prompt: String, maxTokens: Int): Flow<TranslationEvent> = when {
        !active() -> delegate.translate(prompt, maxTokens)
        prompt.contains(TranslationDebug.TRUNCATE_MARKER) -> flowOf(TranslationEvent.Truncated(""))
        prompt.contains(TranslationDebug.CODE_LOSS_MARKER) -> delegate.translate(prompt, maxTokens).map { event ->
            when (event) {
                is TranslationEvent.Delta -> TranslationEvent.Delta(event.text.replace("[[", "["))
                is TranslationEvent.Completed -> TranslationEvent.Completed(event.text.replace("[[", "["))
                else -> event
            }
        }
        else -> delegate.translate(prompt, maxTokens)
    }
}
