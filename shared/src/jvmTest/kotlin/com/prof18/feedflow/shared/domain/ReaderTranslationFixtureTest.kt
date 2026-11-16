package com.prof18.feedflow.shared.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * fork (Phase 3): the reader page carries the translation script, and this
 * test writes a page with the tricky HTML shapes to
 * build/reader-fixtures/translation.html. The block rules themselves run in
 * a browser; open that file to check them by hand or with a browser tool.
 */
class ReaderTranslationFixtureTest {
    private val content = """
        <p id="plain">The first paragraph is plain text with a <a href="https://example.com">link</a> in it.</p>
        <h2 id="heading">A section heading</h2>
        <ul id="mixed"><li>First prepare<p>Detailed explanation</p>Finally check</li><li>A simple item</li></ul>
        <blockquote id="quote"><p>Quoted paragraph one.</p><p>Quoted paragraph two.</p></blockquote>
        <blockquote id="quote-plain">A quote without paragraphs.</blockquote>
        <div id="divbr">Line one of paragraph one.<br>Still paragraph one.<br><br>Paragraph two after a double break.<br>
          <br><br>Paragraph three after a triple break.</div>
        <table id="table"><tr><th>Name</th><th>Meaning</th></tr><tr><td>alpha</td><td>The first letter</td></tr></table>
        <p id="code">Run <code>npm install</code> first, then run <code>npm install</code> again with <code>--force</code>.</p>
        <pre id="pre"><code>const x = 1;
        console.log(x);</code></pre>
        <p id="japanese">これは日本語の段落です。</p>
        <div id="nested"><div><p>Deep paragraph.</p>Loose text after it.</div><span>Inline tail.</span></div>
        <p id="short">OK</p>
        <figure id="figure"><img src="x.png" alt="An image"><figcaption>A caption under the image.</figcaption></figure>
    """.trimIndent()

    @Test
    fun readerPageCarriesTheTranslationScript() {
        val html = getReaderModeStyledHtml(
            colors = null,
            content = content,
            fontSize = 16,
            title = "An article title",
            siteName = "Example Site",
        )
        assertTrue(html.contains("window.feedflowTranslation"))
        assertTrue(html.contains(".__ff_tr"))
        // The script sits in a Kotlin raw string; a stray "$" would be a template mistake.
        assertFalse(readerTranslationJs().contains('$'))

        val out = File("build/reader-fixtures/translation.html")
        out.parentFile.mkdirs()
        out.writeText(html)
    }
}
