package io.github.katsuhidem.tlspike

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Phase 0c measurement runner. Results go to logcat with tag "TLS" as
 * "M <item> key=value ...". Start with:
 *   am start -S -n io.github.katsuhidem.tlspike/.MainActivity --es suite all [--ei threads 4]
 * The model is read from getExternalFilesDir(null)/model.gguf (adb push).
 * suite: smoke | all | eval (eval reads articles.json from the same dir)
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var text: TextView
    private val nCtx = 4096

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(text) })
        if (savedInstanceState != null) return // do not rerun on recreation
        val suite = intent.getStringExtra("suite") ?: return out("idle: no suite")
        val threads = intent.getIntExtra("threads", 4)
        worker.execute {
            try {
                run(suite, threads)
            } catch (t: Throwable) {
                out("M run_fail error=${t.javaClass.simpleName}:${t.message}")
            }
            out("M done suite=$suite")
        }
    }

    private fun run(suite: String, threads: Int) {
        val inputs = JSONObject(assets.open("inputs.json").bufferedReader().readText())
        out("M device page_size=${Os.sysconf(OsConstants._SC_PAGESIZE)} sdk=${Build.VERSION.SDK_INT} threads=$threads")
        mem("before_load")
        val model = File(getExternalFilesDir(null), "model.gguf")
        val t0 = SystemClock.elapsedRealtime()
        if (!Translator.load(model.absolutePath, nCtx, threads)) return out("M load ok=false")
        out("M load ok=true ms=${SystemClock.elapsedRealtime() - t0}")
        mem("after_load")
        when (suite) {
            "smoke" -> smoke(inputs)
            "all" -> all(inputs)
            "eval" -> evalArticles()
        }
        Translator.unload()
        mem("after_unload")
    }

    // ---- suites ----

    private fun smoke(inputs: JSONObject) {
        val title = inputs.getJSONArray("titles").getString(0)
        val r = timed(Prompt.build(title, "en"), title)
        out("M smoke finish=${r.res.finish} ms=${r.ms} out=${r.res.text}")
    }

    private fun all(inputs: JSONObject) {
        val titles = inputs.getJSONArray("titles").strings()
        val paras = inputs.getJSONArray("paragraphs").strings()

        // Determinism: same prompt twice, compare token sequences.
        val p0 = Prompt.build(paras[0], "en")
        val a = Translator.translate(p0, budget(paras[0]))
        val b = Translator.translate(p0, budget(paras[0]))
        out("M determinism equal=${a.tokens.contentEquals(b.tokens)} tokens=${a.tokens.size}/${b.tokens.size}")

        // Titles.
        val one = timed(Prompt.build(titles[0], "en"), titles[0])
        out("M title_one ms=${one.ms} finish=${one.res.finish} out=${one.res.text}")
        val tt = SystemClock.elapsedRealtime()
        titles.forEachIndexed { i, t ->
            val r = timed(Prompt.build(t, "en"), t)
            out("M title i=$i ms=${r.ms} finish=${r.res.finish} out=${r.res.text}")
        }
        out("M titles_20 total_ms=${SystemClock.elapsedRealtime() - tt}")

        // One paragraph: first token and speed.
        val one2 = timed(Prompt.build(paras[1], "en"), paras[1])
        out("M para_one first_token_ms=${one2.firstMs} gen_tokens=${one2.res.tokens.size} gen_ms=${one2.genMs} tok_per_s=${one2.tps()} prompt_tokens=${one2.res.promptTokens}")

        // Twenty paragraphs in a row.
        val tp = SystemClock.elapsedRealtime()
        for (i in 0 until 20) {
            val src = paras[i % paras.size]
            val r = timed(Prompt.build(src, "en"), src)
            out("M para i=$i first_token_ms=${r.firstMs} tok_per_s=${r.tps()} gen_tokens=${r.res.tokens.size} finish=${r.res.finish} ${thermal()} ${memLine()}")
        }
        out("M paras_20 total_ms=${SystemClock.elapsedRealtime() - tp}")
        mem("after_paras")

        cancelTest(paras[2])
        rejectTest()
        codeTest(inputs.getJSONArray("code_paragraphs"))
        zhTest(inputs.getJSONArray("zh_titles").strings())
        ratioTest(paras, titles)
    }

    private fun cancelTest(src: String) {
        repeat(3) { i ->
            val prompt = Prompt.build(src, "en")
            var cancelAt = 0L
            val canceller = Thread {
                Thread.sleep(1500)
                cancelAt = SystemClock.elapsedRealtime()
                Translator.cancel()
            }
            canceller.start()
            val r = Translator.translate(prompt, 1024)
            val doneAt = SystemClock.elapsedRealtime()
            canceller.join()
            out("M cancel i=$i finish=${r.finish} stop_ms=${doneAt - cancelAt} gen_tokens=${r.tokens.size}")
        }
    }

    private fun rejectTest() {
        val long = "word ".repeat(3000)
        val prompt = Prompt.build(long, "en")
        val n = Translator.countTokens(prompt)
        val budget = budgetForTokens(n)
        out("M reject prompt_tokens=$n budget=$budget n_ctx=$nCtx rejected=${n + budget > nCtx}")
    }

    private fun codeTest(arr: JSONArray) {
        var keptNone = 0
        var keptPlaceholder = 0
        var keptGlossary = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val src = o.getString("text")
            val codes = o.getJSONArray("code").strings()

            val none = Translator.translate(Prompt.build(src, "en"), budget(src)).text
            val okNone = codes.all { none.contains(it) }

            var ph = src
            codes.forEachIndexed { k, c -> ph = ph.replace("`$c`", "[[${k + 1}]]") }
            val phOut = Translator.translate(Prompt.build(ph, "en"), budget(ph)).text
            val okPh = codes.indices.all { k -> Regex(Regex.escape("[[${k + 1}]]")).findAll(phOut).count() == 1 }
            var restored = phOut
            codes.forEachIndexed { k, c -> restored = restored.replace("[[${k + 1}]]", "`$c`") }

            val gl = Translator.translate(Prompt.build(src, "en", codes.map { it to it }), budget(src)).text
            val okGl = codes.all { gl.contains(it) }

            if (okNone) keptNone++
            if (okPh) keptPlaceholder++
            if (okGl) keptGlossary++
            out("M code i=$i none=$okNone placeholder=$okPh glossary=$okGl")
            out("M code_out i=$i none=$none")
            out("M code_out i=$i placeholder=$restored")
            out("M code_out i=$i glossary=$gl")
        }
        out("M code_total n=${arr.length()} none=$keptNone placeholder=$keptPlaceholder glossary=$keptGlossary")
    }

    private fun zhTest(titles: List<String>) {
        titles.forEachIndexed { i, t ->
            val auto = Translator.translate(Prompt.build(t), budget(t)).text
            val zh = Translator.translate(Prompt.build(t, "zh"), budget(t)).text
            out("M zh i=$i auto=$auto")
            out("M zh i=$i lang_zh=$zh")
        }
    }

    private fun ratioTest(paras: List<String>, titles: List<String>) {
        val en = (paras + titles)
        val enChars = en.sumOf { it.length }
        val enTokens = en.sumOf { Translator.countTokens(it) - 1 }
        val ja = en.map { Translator.translate(Prompt.build(it, "en"), budget(it)).text }
        val jaChars = ja.sumOf { it.length }
        val jaTokens = ja.sumOf { Translator.countTokens(it) - 1 }
        out("M ratio en_chars=$enChars en_tokens=$enTokens en_chars_per_token=${"%.2f".format(enChars.toDouble() / enTokens)} ja_chars=$jaChars ja_tokens=$jaTokens ja_chars_per_token=${"%.2f".format(jaChars.toDouble() / jaTokens)}")
    }

    /**
     * Human review: translate each paragraph of articles.json
     * ([{"id":1,"url":"...","paragraphs":["..."]}]) and write side-by-side
     * Markdown to eval-out/ for adb pull. Article text never leaves the device
     * and the Mac; it is not committed.
     */
    private fun evalArticles() {
        val dir = getExternalFilesDir(null)!!
        val src = File(dir, "articles.json")
        if (!src.exists()) return out("M eval missing=articles.json")
        val outDir = File(dir, "eval-out").apply { mkdirs() }
        val arr = JSONArray(src.readText())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val id = a.getInt("id")
            val ps = a.getJSONArray("paragraphs").strings()
            val sb = StringBuilder("# 記事 $id\n\n${a.optString("url")}\n\n")
            val t = SystemClock.elapsedRealtime()
            var fails = 0
            ps.forEachIndexed { k, p ->
                val prompt = Prompt.build(p, "en")
                val n = Translator.countTokens(prompt)
                val b = budget(p)
                val r = if (n + b > nCtx) null else Translator.translate(prompt, b)
                if (r == null || r.finish != Translator.Finish.STOP) fails++
                sb.append("## 段落 ${k + 1}\n\n").append(p).append("\n\n")
                sb.append(r?.text ?: "(長すぎるため未翻訳: prompt=$n budget=$b)")
                if (r != null && r.finish != Translator.Finish.STOP) sb.append("\n\n(終了理由: ${r.finish})")
                sb.append("\n\n")
            }
            File(outDir, "article-%02d.md".format(id)).writeText(sb.toString())
            out("M eval id=$id paragraphs=${ps.size} not_stop=$fails ms=${SystemClock.elapsedRealtime() - t}")
        }
    }

    // ---- helpers ----

    private class Timed(val res: Translator.Result, val ms: Long, val firstMs: Long, val genMs: Long) {
        fun tps(): String = if (genMs > 0) "%.1f".format(res.tokens.size * 1000.0 / genMs) else "0"
    }

    /** Runs one translation and measures total time, time to first token and generation time. */
    private fun timed(prompt: String, source: String): Timed {
        val t0 = SystemClock.elapsedRealtime()
        var first = 0L
        val r = Translator.translate(prompt, budget(source)) { n, _ ->
            if (n == 1) first = SystemClock.elapsedRealtime()
        }
        val end = SystemClock.elapsedRealtime()
        val firstMs = if (first > 0) first - t0 else end - t0
        // Generation rate excludes the first token (prompt processing).
        val genMs = if (first > 0) end - first else 0
        return Timed(r, end - t0, firstMs, genMs)
    }

    /** Output budget: max(64, 3 x source tokens). */
    private fun budget(source: String): Int = budgetForTokens(Translator.countTokens(source) - 1)

    private fun budgetForTokens(n: Int): Int = maxOf(64, 3 * n)

    private fun status(key: String): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1

    private fun memLine() = "rss_kb=${status("VmRSS")} hwm_kb=${status("VmHWM")}"

    private fun mem(label: String) = out("M mem at=$label ${memLine()}")

    private fun thermal(): String {
        val pm = getSystemService(PowerManager::class.java)
        return "thermal=${pm.currentThermalStatus} headroom=${"%.2f".format(pm.getThermalHeadroom(0))}"
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun out(line: String) {
        Log.i("TLS", line.replace("\n", "\\n"))
        runOnUiThread { text.append(line + "\n") }
    }
}
