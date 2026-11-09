package org.blaykie.nativeapp.csl.spike

import org.blaykie.nativeapp.docx.CitationAuthor
import org.blaykie.nativeapp.docx.CitationFormatting
import org.blaykie.nativeapp.docx.CitationItem
import org.junit.Assert.*
import org.junit.Test

class CslSpikeTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("csl/$name")) { "Missing asset: $name" }.bufferedReader().use { it.readText() }
    @Test fun renderFiveAndCompareExistingApa() {
        val result = CslSpike.run(asset("citeproc.js"), asset("apa.csl"), asset("locales-en-US.xml"), CslSpike.fixtures(5))
        assertEquals("(Author0, 2020; Author1, 2021; Author2, 2022; Author3, 2023; शर्मा, 2020)", result.parenthetical)
        assertEquals("Author0", result.authorOnly)
        assertEquals("(2020)", result.suppressAuthor)
        assertTrue(result.authorOnly.isNotBlank()); assertTrue(result.suppressAuthor.isNotBlank())
        assertTrue(result.authorOnly.contains("Author0")); assertTrue(result.suppressAuthor.contains("2020"))
        assertEquals(5, result.bibliography.size)
        assertTrue(result.bibliography.contains("Author0, A. (2020). Example title 1. Example Journal, 2, 10–20."))
        assertTrue(result.bibliography.contains("Author1, A. (2021). Example title 2. Example Press."))
        assertTrue(result.bibliography.contains("Author2, A. (2022). Example title 3. https://example.org/page"))
        assertTrue(result.bibliography.contains("Author3, A. (2023). Example title 4. Example Press."))
        assertTrue(result.bibliography.contains("शर्माअनिता. (2020). Example title 5. Example Journal, 2, 10–20."))
        val types = listOf("journalArticle", "book", "webpage", "report", "journalArticle")
        val items = types.mapIndexed { i, type -> CitationItem(key="item$i", title="Example title ${i+1}",
            authors=listOf(CitationAuthor(if (i == 4) "अनिता" else "Alex", if (i == 4) "शर्मा" else "Author$i")),
            date="${2020+i%4}", itemType=type, publicationTitle=if(type=="journalArticle") "Example Journal" else "",
            volume=if(type=="journalArticle") "2" else "", pages=if(type=="journalArticle") "10-20" else "",
            publisher=if(type=="book" || type=="report") "Example Press" else "", url=if(type=="webpage") "https://example.org/page" else "") }
        println("CSL parenthetical: ${result.parenthetical}")
        println("Kotlin parenthetical: ${CitationFormatting.citationText(items)}")
        println("CSL narrative: ${result.authorOnly} ${result.suppressAuthor}")
        println("Kotlin narrative: ${CitationFormatting.citationText(items.take(1), "narrative")}")
        println("COMPARISON TABLE: item | CSL APA bibliography | Kotlin APA bibliography")
        items.forEachIndexed { i, item ->
            val entry = result.bibliography.single { it.contains("Example title ${i+1}", ignoreCase = true) }
            println("${types[i]} | $entry | ${CitationFormatting.referenceList(listOf(item))}")
        }
        println("CSL bibliography:\n${result.bibliography.joinToString("\n")}")
        println("Kotlin bibliography:\n${CitationFormatting.referenceList(items)}")
    }
    @Test fun diagnoseWithSmallJvmThreadStack() {
        println("Citation codec JVM parser: ${org.blaykie.nativeapp.docx.CitationRecordCodec.parserClassName()}")
        val files = listOf("citeproc.js", "apa.csl", "locales-en-US.xml").associateWith(::asset)
        for (target in StagedSpike.Target.values()) {
            val events = java.util.Collections.synchronizedList(mutableListOf<StagedSpike.Event>())
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val worker = Thread(null, Runnable {
                try { StagedSpike.run(target, { files.getValue(it) }) { events.add(it) } }
                catch (t: Throwable) { failure.set(t) }
            }, "csl-test-small-stack", 1024L * 1024L).apply { isDaemon = true; start() }
            worker.join(120_000)
            val timedOut = worker.isAlive
            if (timedOut) { worker.interrupt(); worker.join(2_000) }
            val details = synchronized(events) {
                events.filter { it.status != "start" }.joinToString("; ") {
                    "${it.stage} ${it.status} ${it.ms}ms heap=${it.heapBytes} ${it.detail}"
                }
            }
            val error = failure.get()?.let { " error=${it.javaClass.name}: ${it.message.orEmpty().take(100)}" }.orEmpty()
            println("SMALL_STACK ${target.label}: ${if (timedOut) "TIMEOUT 120s" else "finished"}; $details$error")
            if (worker.isAlive) {
                println("SMALL_STACK: worker did not stop; remaining stages skipped")
                break
            }
            System.gc()
        }
    }

}
