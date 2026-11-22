package org.blaykie.nativeapp.oauthdiag

import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.blaykie.nativeapp.zotero.OAuth1
import org.blaykie.nativeapp.zotero.parseFormEncoded
import org.blaykie.nativeapp.zotero.parseRequestToken
import org.blaykie.nativeapp.zotero.redactOAuthText

private const val REQUEST_URL = "https://www.zotero.org/oauth/request"

/** One variant's outcome, with every credential value already stripped. */
internal data class DiagnosticOutcome(val status: Int?, val body: String, val headerNames: List<String>,
                                      val dateHeader: String?, val errorName: String?)

internal fun diagnosticBodySnippet(body: String): String {
    val snippet = redactOAuthText(body.replace(Regex("\\s+"), " ").trim()).take(120)
    return snippet.ifEmpty { "(empty body)" }
}

internal fun parseHttpDateSeconds(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        format.parse(value)?.time?.div(1000)
    } catch (_: Exception) { null }
}

/** Positive means the server clock is ahead of the device clock. */
internal fun clockOffsetSeconds(dateHeader: String?, deviceSeconds: Long): Long? =
    parseHttpDateSeconds(dateHeader)?.let { it - deviceSeconds }

internal fun diagnosticReport(keyPresent: Boolean, secretPresent: Boolean,
                              lines: List<String>, clockOffset: Long?): String = buildString {
    appendLine("Blaykie OAuth diagnostics (debug only)")
    appendLine("Client key present: $keyPresent")
    appendLine("Client secret present: $secretPresent")
    appendLine("Request URL: $REQUEST_URL")
    appendLine("Clock offset (server - device): " + (clockOffset?.let { "$it s" } ?: "unknown (no Date header)"))
    lines.forEach { appendLine(it) }
}.trimEnd()

/**
 * Tries several shapes of the OAuth 1.0a request-token call and reports what Zotero
 * answered. Debug-only: it never continues to the authorize step and never exposes
 * a credential value, nonce, signature or token.
 */
internal class OAuthDiagnosticsRunner(
    private val consumerKey: String,
    private val consumerSecret: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val nonce: () -> String = { randomNonce() },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private var clockOffset: Long? = null

    fun run(): String {
        val lines = mutableListOf<String>()
        lines += variant("A") { headerRequest("blaykie://oauth-callback") }
        lines += variant("B") { headerRequest("oob") }
        lines += variant("C") { headerRequest("https://github.com/avinashverse6/Blaykie") }
        lines += variant("D") { headerRequest("https://avinashverse6.github.io/blaykie-oauth/callback") }
        lines += variant("E") {
            headerRequest("blaykie://oauth-callback").newBuilder()
                .header("User-Agent", "Blaykie/1.3.0 (Android; oauth-diagnostic)")
                .header("Accept", "*/*")
                .build()
        }
        lines += variant("F") { formBodyRequest("https://github.com/avinashverse6/Blaykie") }
        lines += variant("G") { queryRequest("https://github.com/avinashverse6/Blaykie") }
        return diagnosticReport(consumerKey.isNotBlank(), consumerSecret.isNotBlank(), lines, clockOffset)
    }

    private fun variant(id: String, build: () -> Request): String {
        val outcome = execute(build())
        if (clockOffset == null) clockOffset = clockOffsetSeconds(outcome.dateHeader, nowSeconds())
        val worked = outcome.status == 200 && parseRequestToken(outcome.body) != null
        return buildString {
            when {
                outcome.errorName != null -> appendLine("$id: network error (${outcome.errorName})")
                else -> appendLine("$id: HTTP ${outcome.status}")
            }
            appendLine("  Body: ${diagnosticBodySnippet(outcome.body)}")
            appendLine("  Headers: ${outcome.headerNames.joinToString(", ").ifEmpty { "(none)" }}")
            if (worked) {
                val names = parseFormEncoded(outcome.body).keys.sorted().joinToString(", ")
                appendLine("  WORKED — response parameters: $names (values redacted; not continuing to authorize)")
            }
        }.trimEnd()
    }

    private fun execute(request: Request): DiagnosticOutcome = try {
        calls.newCall(request).execute().use { response ->
            DiagnosticOutcome(response.code, response.body?.string().orEmpty().take(4096),
                response.headers.names().sorted(), response.header("Date"), null)
        }
    } catch (failure: IOException) {
        DiagnosticOutcome(null, "", emptyList(), null, failure.javaClass.simpleName)
    } catch (_: Exception) {
        DiagnosticOutcome(null, "", emptyList(), null, "Error")
    }

    private fun oauthParameters(callback: String): Map<String, String> = linkedMapOf(
        "oauth_consumer_key" to consumerKey,
        "oauth_nonce" to nonce(),
        "oauth_signature_method" to "HMAC-SHA1",
        "oauth_timestamp" to nowSeconds().toString(),
        "oauth_version" to "1.0",
        "oauth_callback" to callback,
    )

    private fun headerRequest(callback: String): Request {
        val header = OAuth1.authorizationHeader("POST", REQUEST_URL,
            consumerKey = consumerKey, consumerSecret = consumerSecret,
            extraOAuth = mapOf("oauth_callback" to callback),
            nonce = nonce(), timestampSeconds = nowSeconds())
        return Request.Builder().url(REQUEST_URL)
            .header("Authorization", header)
            .post(ByteArray(0).toRequestBody(null))
            .build()
    }

    private fun formBodyRequest(callback: String): Request {
        val parameters = oauthParameters(callback)
        val signature = OAuth1.hmacSha1(
            OAuth1.signatureBaseString("POST", REQUEST_URL, parameters), consumerSecret, "")
        val body = (parameters + ("oauth_signature" to signature)).entries
            .joinToString("&") { "${OAuth1.percentEncode(it.key)}=${OAuth1.percentEncode(it.value)}" }
        return Request.Builder().url(REQUEST_URL)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
    }

    private fun queryRequest(callback: String): Request {
        val parameters = oauthParameters(callback)
        val signature = OAuth1.hmacSha1(
            OAuth1.signatureBaseString("POST", REQUEST_URL, parameters), consumerSecret, "")
        val query = (parameters + ("oauth_signature" to signature)).entries
            .joinToString("&") { "${OAuth1.percentEncode(it.key)}=${OAuth1.percentEncode(it.value)}" }
        return Request.Builder().url("$REQUEST_URL?$query")
            .post(ByteArray(0).toRequestBody(null))
            .build()
    }

    private companion object {
        fun randomNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
