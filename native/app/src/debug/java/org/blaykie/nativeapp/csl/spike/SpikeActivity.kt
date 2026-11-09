package org.blaykie.nativeapp.csl.spike

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** Independent debug-only diagnostics, with no interaction with the normal app. */
class SpikeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()
    private var worker: Thread? = null
    private var started = 0L
    private var current = ""
    private var timedOut = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var output: TextView
    private val tag = "BlaykieCslSpike"
    private val ticker = object : Runnable {
        override fun run() {
            val running = worker
            if (running != null && running.isAlive && !timedOut.get()) {
                val elapsed = SystemClock.elapsedRealtime() - started
                status.text = "Stage: $current — ${elapsed / 1000} s"
                if (elapsed >= 120_000 && timedOut.compareAndSet(false, true)) {
                    val line = "stage $current timed out after 120 s; ${elapsed} ms; ${memory()}"
                    addLine(line)
                    status.text = "$line. Waiting for the engine thread to stop."
                    running.interrupt()
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private fun memory(): String {
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        return "heap=$heap bytes, PSS=${Debug.getPss() * 1024L} bytes (snapshots, not peaks)"
    }
    private fun addLine(line: String) {
        lines.add(line); output.text = lines.joinToString("\n")
        Log.i(tag, line)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "Select one diagnostic stage. Each run repeats its prerequisites." }
        output = TextView(this)
        body.addView(status)
        val copy = Button(this).apply { text = "Copy results" }
        copy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CSL spike results", lines.joinToString("\n")))
        }
        body.addView(copy)
        body.addView(Button(this).apply { text = "Run codec self-test"; setOnClickListener { CodecSelfTest.run().forEach(::addLine) } })
        StagedSpike.Target.values().forEach { target ->
            body.addView(Button(this).apply { text = target.label; setOnClickListener { start(target) } })
        }
        body.addView(output)
        setContentView(ScrollView(this).apply { addView(body) })
        addLine("Device=${android.os.Build.MODEL}; Android=${android.os.Build.VERSION.RELEASE}; Rhino interpreted mode requested; thread stack=32 MiB")
        handler.post(ticker)
    }
    private fun start(target: StagedSpike.Target) {
        if (worker?.isAlive == true) {
            status.text = "Previous engine thread is still running or stopping; please wait."
            return
        }
        timedOut = AtomicBoolean(false)
        val timeoutFlag = timedOut
        current = target.label
        started = SystemClock.elapsedRealtime()
        addLine("Requested ${target.label}; each stage has a 120 s watchdog")
        val task = Runnable {
            try {
                StagedSpike.run(target, { name -> assets.open("csl/$name").bufferedReader().use { it.readText() } }) { event ->
                    handler.post {
                        if (!timeoutFlag.get()) {
                            if (event.status == "start") {
                                current = event.stage
                                started = SystemClock.elapsedRealtime()
                                status.text = "Stage: $current — 0 s"
                            } else {
                                val line = "${event.stage}: ${event.status}; ${event.ms} ms; stage heap=${event.heapBytes} bytes; ${memory()}${if (event.detail.isNotEmpty()) "; ${event.detail}" else ""}"
                                addLine(line)
                            }
                        }
                    }
                }
                handler.post { if (!timeoutFlag.get()) status.text = "Completed ${target.label}." }
            } catch (t: Throwable) {
                handler.post {
                    if (!timeoutFlag.get()) {
                        // The stage callback records the failure; this also covers failures outside a stage.
                        val line = "${target.label}: stopped; ${t.javaClass.name}: ${t.message.orEmpty().replace('\n', ' ').take(240)}; ${memory()}"
                        addLine(line); status.text = line
                    }
                }
            }
        }
        worker = Thread(null, task, "CSL-spike-engine", 32L * 1024 * 1024).apply { isDaemon = true; start() }
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        worker?.interrupt()
        super.onDestroy()
    }
}
