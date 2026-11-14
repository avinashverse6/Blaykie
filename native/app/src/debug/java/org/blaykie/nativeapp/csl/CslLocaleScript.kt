package org.blaykie.nativeapp.csl

/** Called with data {style, language, primary, fallback}; always seed missing terms from en-US. */
internal object CslLocaleScript {
    val createEngine = """
        var configure=CSL.Engine.prototype.localeConfigure;
        try {
            CSL.Engine.prototype.localeConfigure=function(spec, shy) {
                if (spec.best !== 'en-US' && !(shy && this.locale[spec.best])) {
                    this.localeSet(CSL.setupXml(data.fallback), 'en-US', spec.best);
                }
                return configure.call(this, spec, shy);
            };
            window.engine=new CSL.Engine({
                retrieveLocale:function(lang) {
                    return String(lang).toLowerCase() === String(data.language).toLowerCase() ? data.primary : data.fallback;
                },
                retrieveItem:function(id){return window.lookup[id];}
            },data.style,data.language,true);
        } finally { CSL.Engine.prototype.localeConfigure=configure; }
    """.trimIndent()
}
