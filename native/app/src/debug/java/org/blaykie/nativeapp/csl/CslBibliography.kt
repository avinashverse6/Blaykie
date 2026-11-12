package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.*
import org.blaykie.nativeapp.citation.processor.*
import org.blaykie.nativeapp.docx.CitationItem

/** Bounded, validated document-level request and response for the local processor. */
internal object CslBibliography {
    fun request(items: List<CitationItem>, clusters: List<CitationCluster>): String {
        require(items.size in 1..300 && clusters.size <= 1000) { "Invalid document size" }
        val records = items.map(CitationItemToCsl::convert)
        CslRenderRequest(records, items.take(5).map { it.key }).toJson()
        require(clusters.map { it.id }.distinct().size == clusters.size) { "Duplicate citation ID" }
        val ids = items.map { it.key }.toSet()
        val values = clusters.map { c ->
            require(c.id.isNotBlank() && c.id.length <= 256 && c.itemIds.size in 1..5 && c.itemIds.distinct().size == c.itemIds.size && c.itemIds.all { it in ids }) { "Invalid citation cluster" }
            require(c.mode in setOf("parenthetical", "narrative") && (c.mode != "narrative" || c.itemIds.size == 1)) { "Unsupported citation mode" }
            require(c.page.length <= 1024 && c.prefix.length <= 1024 && (c.itemIds.size == 1 || (c.page.isBlank() && c.prefix.isBlank()))) { "Unsupported citation affixes" }
            buildJsonObject {
                put("id", c.id); put("mode", c.mode); put("page", c.page.trim()); put("prefix", c.prefix.trim())
                put("itemIds", JsonArray(c.itemIds.map(::JsonPrimitive)))
            }
        }
        return buildJsonObject { put("records", JsonArray(records)); put("clusters", JsonArray(values)) }.toString()
            .also { require(it.length <= 2_000_000) { "Document request too large" } }
    }

    fun parse(text: String, items: List<CitationItem>, clusters: List<CitationCluster>): BibliographyResult {
        require(text.length <= 2_000_000) { "Document response too large" }
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Expected document response")
        require(root.keys == setOf("ok", "ids", "entries", "layout", "citations", "andTerm") && root["ok"] == JsonPrimitive(true)) { "Invalid document response" }
        fun string(value: JsonElement): String {
            val p = value as? JsonPrimitive ?: error("Expected string")
            require(p.isString && p.content.length <= 1_000_000) { "Invalid string" }
            return p.content
        }
        val ids = root.getValue("ids") as? JsonArray ?: error("Missing bibliography IDs")
        val html = root.getValue("entries") as? JsonArray ?: error("Missing bibliography entries")
        require(ids.size == html.size && ids.size in 1..items.size) { "Invalid bibliography size" }
        val known = items.map { it.key }.toSet()
        val entries = ids.zip(html).map { (entryIds, content) ->
            val keys = (entryIds as? JsonArray ?: error("Invalid entry IDs")).map(::string)
            require(keys.isNotEmpty() && keys.all { it in known }) { "Unknown entry ID" }
            BibliographyEntry(keys, CslRichText.parse(string(content)))
        }
        require(entries.flatMap { it.itemIds }.toSet() == known && entries.sumOf { it.itemIds.size } == known.size) { "Incomplete bibliography" }
        val layout = root.getValue("layout") as? JsonObject ?: error("Missing layout")
        require(layout.keys == setOf("hangingIndent", "lineSpacing", "entrySpacing")) { "Invalid layout" }
        fun number(key: String): Double? = when (val v = layout[key]) {
            JsonNull -> null
            is JsonPrimitive -> { require(!v.isString) { "Invalid $key" }; v.doubleOrNull?.also { require(it.isFinite() && it in 0.0..100.0) } ?: error("Invalid $key") }
            else -> error("Invalid $key")
        }
        val hanging = when (val v = layout["hangingIndent"]) { JsonNull -> null; is JsonPrimitive -> { require(!v.isString) { "Invalid hanging indent" }; v.booleanOrNull ?: error("Invalid hanging indent") }; else -> error("Invalid hanging indent") }
        val result = root.getValue("citations") as? JsonArray ?: error("Missing citations")
        require(result.size == clusters.size) { "Invalid citation count" }
        val term = string(root.getValue("andTerm")); require(term.isNotBlank() && term.length <= 256)
        val byId = items.associateBy { it.key }
        val citations = clusters.zip(result).map { (cluster, element) ->
            val row = element as? JsonObject ?: error("Invalid citation")
            require(row.keys == setOf("id", "text", "author", "suppress") && string(row.getValue("id")) == cluster.id) { "Invalid citation order" }
            val rawText = string(row.getValue("text")); val authorText = string(row.getValue("author")); val suppressText = string(row.getValue("suppress"))
            val textValue = if (cluster.mode == "narrative") {
                val authors = byId.getValue(cluster.itemIds.single()).authors
                narrativeAuthor(authorText, term, authors.size, authors.filter { it.group }.map { it.last }) + " " + suppressText
            } else rawText
            require(textValue.isNotBlank() && textValue.length <= 100_000) { "Invalid citation text" }
            ClusterResult(cluster.id, textValue)
        }
        return BibliographyResult(entries, BibliographyLayout(hanging, number("lineSpacing"), number("entrySpacing")), citations)
    }

    fun script(json: String): String = """
        var data=JSON.parse(${JsonPrimitive(json)}), byId={}, work=[], mapping=[];
        data.records.forEach(function(record){byId[record.id]=record;});
        window.lookup=byId;
        data.clusters.forEach(function(c,i){
            function item(id,withPrefix,withPage){var x={id:id}; if(withPrefix && c.prefix)x.prefix=c.prefix; if(withPage && c.page){x.locator=c.page;x.label='page';} return x;}
            function add(suffix, list){work.push({citationID:'blk'+i+suffix,citationItems:list,properties:{noteIndex:0}}); return work.length-1;}
            if(c.mode==='narrative'){
                var a=item(c.itemIds[0],true,false); a['author-only']=true;
                var b=item(c.itemIds[0],false,true); b['suppress-author']=true;
                mapping.push({id:c.id,author:add('a',[a]),suppress:add('b',[b])});
            } else mapping.push({id:c.id,text:add('p',c.itemIds.map(function(id){return item(id,true,true);}))});
        });
        var used={}; data.clusters.forEach(function(c){c.itemIds.forEach(function(id){used[id]=true;});});
        var uncited=data.records.filter(function(record){return !used[record.id];}).map(function(record){return record.id;});
        window.engine.restoreProcessorState([]);
        window.engine.updateUncitedItems(uncited);
        var rebuilt=window.engine.rebuildProcessorState(work,'text',uncited);
        function at(i){if(!rebuilt[i] || typeof rebuilt[i][2] !== 'string')throw Error('Missing citation result');return rebuilt[i][2];}
        var citations=mapping.map(function(m){return {id:m.id,text:m.text===undefined?'':at(m.text),author:m.author===undefined?'':at(m.author),suppress:m.suppress===undefined?'':at(m.suppress)};});
        var bib;
        try {window.engine.setOutputFormat('html'); bib=window.engine.makeBibliography();}
        finally {window.engine.setOutputFormat('text');}
        if(!bib || !bib[0] || !bib[1])throw Error('No bibliography');
        var defined=window.__cslLayoutDefined, meta=bib[0];
        return {ok:true, ids:meta.entry_ids, entries:bib[1], citations:citations, andTerm:window.engine.getTerm('and','long',0),
            layout:{hangingIndent:defined.hanging ? !!meta.hangingindent : null, lineSpacing:defined.line ? meta.linespacing : null, entrySpacing:defined.entry ? meta.entryspacing : null}};
    """.trimIndent()
}
