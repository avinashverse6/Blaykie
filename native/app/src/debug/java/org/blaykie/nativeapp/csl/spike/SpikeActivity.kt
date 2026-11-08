package org.blaykie.nativeapp.csl.spike

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** A separate launcher exists only in debug; no normal app UI is modified. */
class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val result = TextView(this).apply { text = "CSL spike: run on a real phone. Times include local fixture generation where specified." }
        val button = Button(this).apply { text = "Run 50 and 300 item benchmark" }
        view.addView(button); view.addView(result); setContentView(view)
        button.setOnClickListener {
            button.isEnabled = false; result.text = "Running…"
            thread {
                try {
                    fun asset(name: String) = assets.open("csl/$name").bufferedReader().use { it.readText() }
                    val js = asset("citeproc.js"); val style = asset("apa.csl"); val locale = asset("locales-en-US.xml")
                    val text = buildString {
                        appendLine("Memory is process PSS and JVM heap; samples are snapshots, not peak memory.")
                        for (count in listOf(50, 300)) {
                            val fixtures = CslSpike.fixtures(count)
                            val beforePss = Debug.getPss() * 1024L
                            val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                            val start = System.nanoTime()
                            val rendered = CslSpike.run(js, style, locale, fixtures)
                            val totalMs = (System.nanoTime() - start) / 1_000_000
                            val afterPss = Debug.getPss() * 1024L
                            val heapAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                            appendLine("$count items: fresh engine total $totalMs ms; render ${rendered.renderMs} ms; initialization approx ${totalMs-rendered.renderMs} ms; PSS $beforePss → $afterPss bytes; heap $heapBefore → $heapAfter bytes; ${rendered.bibliography.size} references.")
                        }
                        appendLine("First run after process launch approximates cold start; repeat runs are warm. Device and Android version must be recorded separately.")
                    }
                    runOnUiThread { result.text = text; button.isEnabled = true }
                } catch (e: Exception) {
                    runOnUiThread { result.text = "Spike failed: ${e.stackTraceToString()}"; button.isEnabled = true }
                }
            }
        }
    }
}
