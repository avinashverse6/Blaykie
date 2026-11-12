package org.blaykie.nativeapp.csl.spike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.blaykie.nativeapp.csl.CslSafeXml
import org.blaykie.nativeapp.csl.CslWebViewHost
import org.blaykie.nativeapp.csl.CslRenderRequest
import org.blaykie.nativeapp.csl.StyleRegistry

/** Debug-only, detached WebView: all executable code and data are bundled locally. */
class WebViewSpikeActivity : Activity() {
    private enum class Target(val title: String, val count: Int = 0) {
        ALL("Run all stages"), CREATE("WebView creation"), SCRIPT("Load citeproc.js"), ENGINE("Create CSL engine"),
        ONE("Render 1 item", 1), FIVE("Render 5 items", 5), FIFTY("Render 50 items", 50), THREE_HUNDRED("Render 300 items", 300)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()
    private lateinit var status: TextView
    private lateinit var output: TextView
    private val hostScope = MainScope()
    private var selfTestHost: CslWebViewHost? = null
    private var web: WebView? = null
    private var active = false
    private var generation = 0
    private var current = ""
    private var started = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (active) {
                val elapsed = SystemClock.elapsedRealtime() - started
                status.text = "Stage: $current — ${elapsed / 1000} s"
                if (elapsed >= 120_000) {
                    val message = "stage $current timed out after 120 s; ${elapsed} ms; ${memory()}"
                    line(message); status.text = message
                    closeRun()
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private fun memory(): String {
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        return "heap=$heap bytes; PSS=${Debug.getPss() * 1024L} bytes (app process snapshots, not WebView renderer or peak)"
    }
    private fun line(s: String) { lines.add(s); output.text = lines.joinToString("\n"); Log.i("BlaykieCslWebView", s) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "Choose a stage. Each run repeats its prerequisites." }
        output = TextView(this)
        body.addView(status)
        body.addView(Button(this).apply {
            text = "Copy results"
            setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CSL WebView results", lines.joinToString("\n"))) }
        })
        body.addView(Button(this).apply { text = "Run codec self-test"; setOnClickListener { CodecSelfTest.run().forEach(::line) } })
        body.addView(Button(this).apply { text = "Host self-test"; setOnClickListener { hostSelfTest() } })
        Target.values().forEach { target -> body.addView(Button(this).apply { text = target.title; setOnClickListener { start(target) } }) }
        body.addView(output)
        setContentView(ScrollView(this).apply { addView(body) })
        val provider = try {
            if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "not available or provider not initialized"
            else "provider version unavailable on Android below 8"
        } catch (t: Throwable) { "unavailable: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}" }
        line("Device=${Build.MODEL}; Android=${Build.VERSION.RELEASE}; WebView provider=$provider")
        handler.post(ticker)
    }
    private fun registrySelfTest() {
        val parser = try { CslSafeXml.parserClassName() } catch (t: Throwable) { "unavailable (${t.javaClass.name})" }
        fun test(name: String, reject: Boolean, operation: () -> Unit) {
            try {
                operation()
                line("${if (reject) "FAIL" else "PASS"} registry $name: exception=none; parser=$parser")
            } catch (t: Throwable) {
                line("${if (reject && t is IllegalArgumentException) "PASS" else "FAIL"} registry $name: exception=${t.javaClass.name}; parser=$parser")
            }
        }
        test("bundled APA", false) { assets.open("csl/apa.csl").use { StyleRegistry.read("csl/apa.csl", it) } }
        test("DOCTYPE", true) { StyleRegistry.read("bad.csl", "<!DOCTYPE style><style/>".byteInputStream()) }
        test("ENTITY", true) { StyleRegistry.read("bad.csl", "<!ENTITY x 'bad'><style/>".byteInputStream()) }
        test("oversized", true) { StyleRegistry.read("bad.csl", ("<style>" + "x".repeat(CslSafeXml.MAX_BYTES) + "</style>").byteInputStream()) }
    }
    private fun hostSelfTest() {
        if (selfTestHost != null) { line("Host self-test already running"); return }
        val host = CslWebViewHost(applicationContext)
        selfTestHost = host
        hostScope.launch {
            val total = SystemClock.elapsedRealtime()
            try {
                registrySelfTest()
                val provider = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "not available" else "unavailable below Android 8"
                line("Host self-test WebView provider: $provider")
                val styleId = StyleRegistry.load({ assets.open(it) }).single { it.asset == "csl/apa.csl" }.id
                val warmStart = SystemClock.elapsedRealtime()
                val warm = host.warmUp(styleId)
                line("${if (warm.isSuccess) "PASS" else "FAIL"} host warm-up: ${SystemClock.elapsedRealtime() - warmStart} ms ${warm.exceptionOrNull()?.message.orEmpty()}")
                if (warm.isFailure) return@launch
                val records = Json.parseToJsonElement(CslSpike.fixtures(5).toString()).jsonArray.map { it.jsonObject }
                val ids = records.map { it.getValue("id").jsonPrimitive.content }
                val start = SystemClock.elapsedRealtime()
                val result = host.render(CslRenderRequest(records, ids))
                val output = result.getOrNull()
                val passed = output != null && output.cluster == "(Author0, 2020; Author1, 2021; Author2, 2022; Author3, 2023; शर्मा, 2020)" && output.author == "Author0" && output.suppress == "(2020)" && output.bibliography.size == 5
                line("${if (passed) "PASS" else "FAIL"} host render: ${SystemClock.elapsedRealtime() - start} ms ${result.exceptionOrNull()?.message.orEmpty()}")
                if (output != null) { line("Host cluster: ${output.cluster}"); line("Host author: ${output.author}; suppress: ${output.suppress}"); output.bibliography.forEach { line("Host bibliography: $it") } }
            } catch (t: Throwable) {
                line("FAIL host self-test: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}")
            } finally {
                host.destroy(); selfTestHost = null
                line("Host destroyed; total ${SystemClock.elapsedRealtime() - total} ms")
            }
        }
    }
    private fun begin(name: String) { current = name; started = SystemClock.elapsedRealtime(); status.text = "Stage: $name — 0 s" }
    private fun done(detail: String = "") {
        val duration = SystemClock.elapsedRealtime() - started
        line("$current: done; $duration ms; ${memory()}${if (detail.isNotEmpty()) "; $detail" else ""}")
    }
    private fun fail(t: Throwable) = fail("${t.javaClass.name}: ${t.message.orEmpty().replace('\n', ' ').take(240)}")
    private fun fail(message: String) {
        if (!active) return
        val text = "$current: error; ${SystemClock.elapsedRealtime() - started} ms; ${memory()}; ${message.take(300)}"
        line(text); status.text = text; closeRun()
    }
    private fun closeRun() {
        active = false; generation++
        val old = web; web = null
        try { old?.stopLoading(); old?.destroy() } catch (_: Throwable) { }
    }
    private fun complete(target: Target) { status.text = "Completed ${target.title}."; closeRun() }
    @SuppressLint("SetJavaScriptEnabled")
    private fun start(target: Target) {
        if (active) { status.text = "A stage is already running."; return }
        active = true; generation++; val run = generation
        line("Requested ${target.title}; fresh WebView and prerequisites; 120 s per stage")
        begin(Target.CREATE.title)
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            val view = WebView(this)
            web = view
            view.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                blockNetworkImage = true
                domStorageEnabled = false
            }
            view.webViewClient = object : WebViewClient() {
                private var loaded = false
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse = denied()
                @Suppress("DEPRECATION")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse = denied()
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!active || run != generation || loaded) return
                    loaded = true
                    done("detached local in-memory page; JavaScript enabled; network/file/content blocked")
                    if (Build.VERSION.SDK_INT >= 26) {
                        val p = try { WebView.getCurrentWebViewPackage() } catch (_: Throwable) { null }
                        line("WebView provider after initialization: ${p?.packageName ?: "unknown"} ${p?.versionName.orEmpty()}")
                    }
                    if (target == Target.CREATE) complete(target) else loadScript(target, run)
                }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    if (run == generation) fail("WebView renderer exited (crashed=${detail?.didCrash()})")
                    return true
                }
            }
            // No URL or local file is exposed to the page. Assets are injected as JSON strings only.
            view.loadDataWithBaseURL(null, "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; connect-src 'none'; img-src 'none'\"></head><body></body></html>", "text/html", "UTF-8", null)
        } catch (t: Throwable) {
            fail("WebView unavailable or initialization failed: ${t.javaClass.name}: ${t.message.orEmpty().take(200)}")
        }
    }
    private fun denied(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun asset(name: String): String = assets.open("csl/$name").use { if (name.endsWith(".xml") || name.endsWith(".csl")) CslSafeXml.parse(it).first else it.bufferedReader(Charsets.UTF_8).readText() }
    /** All messages are JSON: a fixed operation and quoted data; no JS interface is installed. */
    private fun evaluate(run: Int, script: String, callback: (JSONObject) -> Unit) {
        if (!active || run != generation) return
        try {
            val wrapper = WebViewLoader.wrap(script)
            web?.evaluateJavascript(wrapper) { raw ->
                if (!active || run != generation) return@evaluateJavascript
                try {
                    val json = JSONTokener(raw ?: "null").nextValue()
                    require(json is String) { "WebView returned a non-string JSON envelope" }
                    val message = JSONObject(json)
                    require(message.has("ok") && message.get("ok") is Boolean) { "Invalid WebView response" }
                    if (!message.getBoolean("ok")) fail("JavaScript error: ${message.optString("error").take(240)}") else callback(message)
                } catch (t: Throwable) { fail(t) }
            } ?: fail("WebView unavailable")
        } catch (t: Throwable) { fail(t) }
    }
    private fun loadScript(target: Target, run: Int) {
        begin(Target.SCRIPT.title)
        try {
            val text = asset("citeproc.js")
            // Keep individual IPC messages well below Binder limits; never alter the asset.
            val chunks = mutableListOf<String>(); var start = 0
            while (start < text.length) {
                var end = minOf(start + 12_000, text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                chunks.add(text.substring(start, end)); start = end
            }
            evaluate(run, "window.__cslChunks=[]; return {ok:true};") {
                fun send(index: Int) {
                    if (!active || run != generation) return
                    if (index == chunks.size) {
                        line("Loader diagnosis: the previous indirect eval ran a strict-mode script in an eval-local scope; Node vm.runInContext ran it as a global script. This loader inserts a local script element.")
                        line("Loader wrapper first 120 characters: ${WebViewLoader.wrap(WebViewLoader.body).take(120).replace('\n', ' ')}")
                        evaluate(run, WebViewLoader.body) { result ->
                            val detail = "received script length=${result.getInt("length")} UTF-16 code units; typeof CSL=${result.getString("csl")}; typeof window.CSL=${result.getString("windowCsl")}; typeof globalThis.CSL=${result.getString("globalCsl")}; script error=${result.optString("scriptError")}"
                            line("Loader diagnostics: $detail")
                            if (result.getString("windowCsl") != "object") {
                                fail("CSL missing after global script load; $detail")
                            } else {
                                done("citeproc.js loaded at global scope; $detail")
                                if (target == Target.SCRIPT) complete(target) else createEngine(target, run)
                            }
                        }
                    } else evaluate(run, "window.__cslChunks.push(${JSONObject.quote(chunks[index])}); return {ok:true};") { send(index + 1) }
                }
                send(0)
            }
        } catch (t: Throwable) { fail(t) }
    }
    private fun createEngine(target: Target, run: Int) {
        begin(Target.ENGINE.title)
        try {
            val data = JSONObject().put("style", asset("apa.csl")).put("locale", asset("locales-en-US.xml"))
            evaluate(run, """
                var data = JSON.parse(${JSONObject.quote(data.toString())});
                window.lookup={}; window.ids=[];
                window.engine=new CSL.Engine({retrieveLocale:function(){return data.locale;},retrieveItem:function(id){return window.lookup[id];}},data.style,'en-US');
                window.engine.setOutputFormat('text'); return {ok:true};
            """.trimIndent()) {
                done()
                if (target == Target.ENGINE) complete(target) else render(if (target == Target.ALL) Target.ONE else target, run, target == Target.ALL)
            }
        } catch (t: Throwable) { fail(t) }
    }
    private fun render(target: Target, run: Int, runAll: Boolean) {
        begin(target.title)
        try {
            val items = CslSpike.fixtures(target.count).toString()
            evaluate(run, """
                var records=JSON.parse(${JSONObject.quote(items)});
                window.lookup={}; window.ids=[];
                records.forEach(function(item){window.lookup[item.id]=item;window.ids.push(item.id);});
                window.engine.updateItems(window.ids);
                var cluster=window.engine.makeCitationCluster(window.ids.slice(0,5).map(function(id){return {id:id};}));
                var author=window.engine.makeCitationCluster([{id:window.ids[0],'author-only':true}]);
                var suppress=window.engine.makeCitationCluster([{id:window.ids[0],'suppress-author':true}]);
                var bib=window.engine.makeBibliography();
                return {ok:true,cluster:cluster,author:author,suppress:suppress,bibliography:bib[1]};
            """.trimIndent()) { message ->
                val bib = message.getJSONArray("bibliography")
                require(bib.length() == target.count) { "Expected ${target.count} references, got ${bib.length()}" }
                done("${bib.length()} references")
                if (target == Target.FIVE) compare(message, bib)
                val next = if (!runAll) null else when (target) {
                    Target.ONE -> Target.FIVE
                    Target.FIVE -> Target.FIFTY
                    Target.FIFTY -> Target.THREE_HUNDRED
                    else -> null
                }
                if (next != null) render(next, run, true) else complete(if (runAll) Target.ALL else target)
            }
        } catch (t: Throwable) { fail(t) }
    }
    private fun compare(message: JSONObject, bib: JSONArray) {
        val expected = listOf(
            "Author0, A. (2020). Example title 1. Example Journal, 2, 10–20.",
            "Author1, A. (2021). Example title 2. Example Press.",
            "Author2, A. (2022). Example title 3. https://example.org/page",
            "Author3, A. (2023). Example title 4. Example Press.",
            "शर्माअनिता. (2020). Example title 5. Example Journal, 2, 10–20."
        )
        val fields = linkedMapOf("cluster" to "(Author0, 2020; Author1, 2021; Author2, 2022; Author3, 2023; शर्मा, 2020)", "author" to "Author0", "suppress" to "(2020)")
        var differences = 0
        fields.forEach { (key, value) ->
            val actual = message.getString(key)
            line("WebView $key: $actual")
            if (actual != value) { differences++; line("DIFFERENCE $key: JVM=$value; WebView=$actual") }
        }
        val actual = (0 until bib.length()).map { bib.getString(it).trim() }
        actual.forEachIndexed { index, text -> line("WebView bibliography ${index + 1}: $text") }
        expected.filterNot { it in actual }.forEach { differences++; line("DIFFERENCE missing JVM bibliography: $it") }
        actual.filterNot { it in expected }.forEach { differences++; line("DIFFERENCE extra WebView bibliography: $it") }
        line("Five-item comparison vs JVM golden text: $differences differences (bibliography order not compared)")
    }
    override fun onDestroy() { selfTestHost?.destroy(); hostScope.cancel(); handler.removeCallbacks(ticker); closeRun(); super.onDestroy() }
}
