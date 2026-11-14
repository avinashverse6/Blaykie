package org.blaykie.nativeapp.csl

import kotlinx.serialization.json.JsonPrimitive

/** Fixed script: only the validated JSON string is embedded as data. */
internal object CslRenderScript {
    fun body(json: String): String = """
        var data=JSON.parse(${JsonPrimitive(json)});
        window.lookup={}; window.ids=[];
        data.records.forEach(function(item){window.lookup[item.id]=item;window.ids.push(item.id);});
        window.engine.updateItems(window.ids);
        function item(id, withPrefix, withPage) {
            var result={id:id};
            if (withPrefix && data.prefix) result.prefix=data.prefix;
            if (withPage && data.page) { result.locator=data.page; result.label='page'; }
            return result;
        }
        var cluster=window.engine.makeCitationCluster(data.citationIds.map(function(id){return item(id,true,true);}));
        var authorItem=item(data.citationIds[0],true,false); authorItem['author-only']=true;
        var suppressItem=item(data.citationIds[0],false,true); suppressItem['suppress-author']=true;
        var author=window.engine.makeCitationCluster([authorItem]);
        var suppress=window.engine.makeCitationCluster([suppressItem]);
        var bib=window.engine.makeBibliography();
        return {ok:true,cluster:cluster,author:author,suppress:suppress,bibliography:bib[1],andTerm:window.engine.getTerm("and", "long", 0)};
    """.trimIndent()
}
