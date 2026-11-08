package org.blaykie.nativeapp.session

import java.io.File

/** Messages, causes, thread names, and arbitrary frame fields can contain private data. */
object CrashLog {
    @Volatile private var installed = false
    private val knownClasses = listOf("MainActivity", "DocxProject", "SessionStore", "ZoteroClient", "CrashLog")
    private fun frameName(frame: StackTraceElement): String {
        if (!frame.className.startsWith("org.blaykie.nativeapp.")) return "<external>"
        val simple = frame.className.substringAfterLast('.').substringBefore('$')
        return if (simple in knownClasses) simple else "<application>"
    }
    fun sanitized(error: Throwable): String = buildString {
        append(error.javaClass.name).append('\n')
        for (frame in error.stackTrace) append("    at ").append(frameName(frame)).append(':').append(frame.lineNumber).append('\n')
    }
    @Synchronized fun install(root: File) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            try { File(root, "crash.log").writeText(sanitized(error), Charsets.UTF_8) } catch (_: Exception) { }
            // Do not forward the original message, causes, thread name or frame fields to logcat.
            val safe = RuntimeException()
            safe.stackTrace = error.stackTrace.map { StackTraceElement("Blaykie", frameName(it), null, it.lineNumber) }.toTypedArray()
            previous?.uncaughtException(Thread("Blaykie"), safe)
        }
    }
}
