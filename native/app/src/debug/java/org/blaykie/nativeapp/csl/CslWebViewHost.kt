package org.blaykie.nativeapp.csl

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.blaykie.nativeapp.csl.spike.WebViewLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import org.blaykie.nativeapp.citation.processor.BibliographyResult
import org.blaykie.nativeapp.citation.processor.CitationCluster
import org.blaykie.nativeapp.docx.CitationItem

/** One detached host per document session. Only the application context is retained. */
internal class CslWebViewHost(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val machine = CslHostStateMachine()
    private var view: WebView? = null
    private var pending: CompletableDeferred<String>? = null
    val state: CslHostState get() = machine.state

    private fun closeView() {
        pending?.cancel(); pending = null
        val old = view; view = null
        try { old?.stopLoading(); old?.destroy() } catch (_: Throwable) { }
    }
    fun cancel() = onMain { machine.cancel(); closeView() }
    fun destroy() = onMain { machine.destroy(); closeView() }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun asset(name: String) = app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun failed(token: Long, message: String): Result<Nothing> {
        machine.fail(token, message)
        closeView()
        return Result.failure(IllegalStateException(message))
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun warmUp(styleId: String, language: String = "en-US"): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        lock.withLock {
            val token = machine.warmUp(styleId) ?: return@withLock Result.failure(IllegalStateException("Invalid style or host destroyed"))
            closeView()
            try {
                withTimeout(120_000) {
                    val styles = StyleRegistry.load({ app.assets.open(it) }, File(app.filesDir, "styles"))
                    val style = styles.singleOrNull { styleSelectionId(it) == styleId } ?: error("Citation style not found or ambiguous")
                    val resolved = effectiveStyle(style, styles) { metadata ->
                        when (metadata.source) {
                            StyleSource.BUNDLED -> app.assets.open(metadata.asset)
                            StyleSource.PRIVATE -> File(app.filesDir, "styles/${metadata.asset}").inputStream()
                        }
                    }
                    val styleText = resolved.text
                    val parsedStyle = CslSafeXml.parse(styleText.byteInputStream(Charsets.UTF_8))
                    val bibliography = parsedStyle.second.getElementsByTagNameNS("http://purl.org/net/xbiblio/csl", "bibliography")
                    val element = if (bibliography.length == 1) bibliography.item(0) as org.w3c.dom.Element else null
                    val layout = JSONObject().put("hanging", element?.hasAttribute("hanging-indent") == true)
                        .put("line", element?.hasAttribute("line-spacing") == true).put("entry", element?.hasAttribute("entry-spacing") == true)
                    val fallback = app.assets.open("csl/locales-en-US.xml").use { CslSafeXml.parse(it).first }
                    val primary = if (language.equals("en-US", true)) fallback else {
                        val store = LocaleImportStore(File(app.filesDir, "locales"))
                        val metadata = store.list().singleOrNull { it.tag.equals(language, true) } ?: error("Language unavailable")
                        File(app.filesDir, "locales/${metadata.asset}").inputStream().use {
                            val bytes = it.readBytes()
                            require(bytes.size <= LocaleImportStore.MAX_BYTES)
                            require(LocaleImportStore.read(metadata.asset, bytes.inputStream()) == metadata)
                            CslSafeXml.parse(bytes.inputStream()).first
                        }
                    }
                    val script = asset("csl/citeproc.js")
                    WebView.setWebContentsDebuggingEnabled(false)
                    val web = WebView(app)
                    view = web
                    web.settings.apply {
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
                    val page = CompletableDeferred<String>(); pending = page
                    web.webViewClient = object : WebViewClient() {
                        private var loaded = false
                        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = denied()
                        @Suppress("DEPRECATION") override fun shouldInterceptRequest(view: WebView?, url: String?) = denied()
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                        @Suppress("DEPRECATION") override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                        override fun onPageFinished(view: WebView?, url: String?) { if (!loaded) { loaded = true; page.complete("ready") } }
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            pending?.completeExceptionally(IllegalStateException("WebView renderer exited"))
                            machine.fail(token, "WebView renderer exited")
                            closeView()
                            return true
                        }
                    }
                    web.loadDataWithBaseURL(null, "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; connect-src 'none'; img-src 'none'\"></head><body></body></html>", "text/html", "UTF-8", null)
                    page.await(); pending = null
                    evaluate("window.__cslChunks=[]; return {ok:true};")
                    var start = 0
                    while (start < script.length) {
                        var end = minOf(start + 12_000, script.length)
                        if (end < script.length && Character.isHighSurrogate(script[end - 1]) && Character.isLowSurrogate(script[end])) end--
                        evaluate("window.__cslChunks.push(${JSONObject.quote(script.substring(start, end))}); return {ok:true};")
                        start = end
                    }
                    val loaded = evaluate(WebViewLoader.body)
                    require(loaded.getString("windowCsl") == "object") { "CSL did not load" }
                    val data = JSONObject().put("style", styleText).put("primary", primary).put("fallback", fallback)
                        .put("language", language)
                    evaluate("""
                        var data=JSON.parse(${JSONObject.quote(data.toString())});
                        window.lookup={}; window.ids=[];
                        window.__cslLayoutDefined=JSON.parse(${JSONObject.quote(layout.toString())});
                        ${CslLocaleScript.createEngine}
                        window.engine.setOutputFormat('text'); return {ok:true};
                    """.trimIndent())
                    check(machine.ready(token)) { "Warm-up cancelled" }
                    Result.success(Unit)
                }
            } catch (e: TimeoutCancellationException) { failed(token, "Citation style preparation timed out") }
            catch (e: Throwable) { failed(token, "Citation style unavailable: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private suspend fun evaluate(script: String): JSONObject {
        val web = view ?: error("WebView unavailable")
        val result = CompletableDeferred<String>(); pending = result
        web.evaluateJavascript(WebViewLoader.wrap(script)) { raw -> result.complete(raw ?: "null") }
        val raw = result.await(); pending = null
        require(raw.length <= 4_000_000) { "WebView response too large" }
        val envelope = JSONTokener(raw).nextValue()
        require(envelope is String) { "Invalid WebView envelope" }
        val message = JSONObject(envelope)
        require(message.has("ok") && message.get("ok") is Boolean) { "Invalid WebView response" }
        require(message.getBoolean("ok")) { "JavaScript error: ${message.optString("error").take(240)}" }
        return message
    }

    suspend fun render(request: CslRenderRequest): Result<CslRenderResponse> = withContext(Dispatchers.Main.immediate) {
        lock.withLock {
            val token = machine.renderToken() ?: return@withLock Result.failure(IllegalStateException("Citation style is not ready"))
            try {
                val json = request.toJson()
                withTimeout(120_000) {
                    val message = evaluate(CslRenderScript.body(json))
                    Result.success(CslRenderResponse.parse(message.toString(), request.records.size))
                }
            } catch (e: TimeoutCancellationException) { failed(token, "Citation rendering timed out") }
            catch (e: Throwable) { failed(token, "Citation rendering failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
    suspend fun bibliography(items: List<CitationItem>, clusters: List<CitationCluster>): Result<BibliographyResult> = withContext(Dispatchers.Main.immediate) {
        lock.withLock {
            val token = machine.renderToken() ?: return@withLock Result.failure(IllegalStateException("Citation style is not ready"))
            try {
                val request = CslBibliography.request(items, clusters)
                withTimeout(120_000) {
                    val result = evaluate(CslBibliography.script(request))
                    Result.success(CslBibliography.parse(result.toString(), items, clusters))
                }
            } catch (e: TimeoutCancellationException) { failed(token, "Bibliography rendering timed out") }
            catch (e: Throwable) { failed(token, "Bibliography rendering failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

}
