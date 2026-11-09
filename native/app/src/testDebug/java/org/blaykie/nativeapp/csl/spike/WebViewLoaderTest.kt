package org.blaykie.nativeapp.csl.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class WebViewLoaderTest {
    @Test fun strictEvalRegressionAndSameLoaderRunsAtGlobalScopeInV8() {
        val file = File(requireNotNull(javaClass.classLoader?.getResource("csl/citeproc.js")).toURI())
        val script = """
            const vm = require('vm'), fs = require('fs');
            const source = fs.readFileSync(process.argv[1], 'utf8');
            const ctx = vm.createContext({});
            ctx.window = ctx;
            ctx.__cslChunks = [source];
            ctx.addEventListener = function(){};
            ctx.removeEventListener = function(){};
            ctx.document = {
                createElement: function(){return {textContent:'',remove:function(){}};},
                head: {appendChild:function(e){vm.runInContext(e.textContent, ctx);}}
            };
            const previous = vm.runInContext("(0,eval)(window.__cslChunks.join('')); typeof window.CSL", ctx);
            if (previous !== 'undefined') throw Error('expected strict eval regression; got ' + previous);
            const result = JSON.parse(vm.runInContext(process.argv[2], ctx));
            if (!result.ok || result.csl !== 'object' || result.windowCsl !== 'object' || result.globalCsl !== 'object' || result.length !== source.length) {
                throw Error('Global loader failed: ' + JSON.stringify(result));
            }
            console.log('strict indirect eval: ' + previous + '; global script loader: ' + JSON.stringify(result));
        """.trimIndent()
        val process = ProcessBuilder("node", "-e", script, file.absolutePath, WebViewLoader.wrap(WebViewLoader.body))
            .redirectErrorStream(true).start()
        assertTrue("Node/V8 loader test timed out", process.waitFor(60, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        println("WebView loader regression: $output")
        assertEquals("Node/V8 loader failed: $output", 0, process.exitValue())
    }
}
