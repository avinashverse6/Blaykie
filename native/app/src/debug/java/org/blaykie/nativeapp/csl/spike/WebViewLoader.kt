package org.blaykie.nativeapp.csl.spike

/** Shared by the Android page and the JVM/V8 regression test. Contains no upstream source. */
internal object WebViewLoader {
    const val body = """
        var source = window.__cslChunks.join('');
        var n = source.length;
        var error = '';
        var listener = function(event) { error = String(event.message || 'script error').slice(0, 200); };
        window.addEventListener('error', listener);
        var element = document.createElement('script');
        element.textContent = source;
        document.head.appendChild(element);
        element.remove();
        window.removeEventListener('error', listener);
        delete window.__cslChunks;
        return {ok:true, length:n, csl:typeof CSL, windowCsl:typeof window.CSL,
            globalCsl:typeof globalThis === 'undefined' ? 'unavailable' : typeof globalThis.CSL, scriptError:error};
    """
    fun wrap(script: String): String = "JSON.stringify((function(){try { $script } catch(e) { return {ok:false,error:String(e && (e.stack || e.message) || e).slice(0,500)}; }})())"
}
