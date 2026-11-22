package org.blaykie.nativeapp.zotero

import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.blaykie.nativeapp.BuildConfig

internal data class OAuthRequestToken(val token: String, val secret: String) {
    override fun toString() = "OAuthRequestToken(redacted)"
}

internal data class OAuthAccessToken(val apiKey: String, val userId: Long, val username: String) {
    override fun toString() = "OAuthAccessToken(redacted)"
}

internal sealed class OAuthResult<out T> {
    class Success<T>(val value: T) : OAuthResult<T>()
    object NetworkFailure : OAuthResult<Nothing>()
    object Rejected : OAuthResult<Nothing>()
    object Malformed : OAuthResult<Nothing>()
    final override fun toString() = "OAuthResult.${javaClass.simpleName}"
}

internal data class OAuthCallback(val token: String?, val verifier: String?)

/** Decodes an application/x-www-form-urlencoded body such as Zotero's token responses. */
internal fun parseFormEncoded(body: String): Map<String, String> =
    body.split('&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val split = part.indexOf('=')
        val key = if (split >= 0) part.substring(0, split) else part
        val value = if (split >= 0) part.substring(split + 1) else ""
        runCatching {
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }.getOrNull()
    }.toMap()

internal fun parseRequestToken(body: String): OAuthRequestToken? {
    val values = parseFormEncoded(body)
    val token = values["oauth_token"]?.takeIf { it.isNotBlank() } ?: return null
    val secret = values["oauth_token_secret"]?.takeIf { it.isNotBlank() } ?: return null
    return OAuthRequestToken(token, secret)
}

internal fun parseAccessToken(body: String): OAuthAccessToken? {
    val values = parseFormEncoded(body)
    // For Zotero, oauth_token_secret of the access-token response IS the API key.
    val apiKey = values["oauth_token_secret"]?.takeIf { it.isNotBlank() } ?: return null
    val userId = values["userID"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return OAuthAccessToken(apiKey, userId, values["username"].orEmpty())
}

/** Only blaykie://oauth-callback callbacks are accepted; anything else is ignored. */
internal fun parseOAuthCallback(url: String): OAuthCallback? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals(ZoteroOAuthClient.CALLBACK_SCHEME, ignoreCase = true) ||
        !uri.host.equals(ZoteroOAuthClient.CALLBACK_HOST, ignoreCase = true)) return null
    val values = parseFormEncoded(uri.rawQuery.orEmpty())
    return OAuthCallback(values["oauth_token"], values["oauth_verifier"])
}

internal class ZoteroOAuthClient(
    private val consumerKey: String,
    private val consumerSecret: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val baseUrl: String = "https://www.zotero.org/oauth/",
    private val nonce: () -> String = { randomNonce() },
    private val timestampSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Read-only access is all Blaykie asks for at sign-in. */
    fun authorizeUrl(requestToken: String): String =
        "https://www.zotero.org/oauth/authorize" +
            "?oauth_token=${OAuth1.percentEncode(requestToken)}" +
            "&name=Blaykie&library_access=1&notes_access=0&write_access=0&all_groups=read"

    fun requestToken(): OAuthResult<OAuthRequestToken> {
        val url = baseUrl + "request"
        val header = OAuth1.authorizationHeader("POST", url,
            consumerKey = consumerKey, consumerSecret = consumerSecret,
            extraOAuth = mapOf("oauth_callback" to CALLBACK),
            nonce = nonce(), timestampSeconds = timestampSeconds())
        return post(url, header) { body -> parseRequestToken(body)?.let { OAuthResult.Success(it) } ?: OAuthResult.Malformed }
    }

    fun accessToken(requestToken: String, requestSecret: String, verifier: String): OAuthResult<OAuthAccessToken> {
        val url = baseUrl + "access"
        val header = OAuth1.authorizationHeader("POST", url,
            consumerKey = consumerKey, consumerSecret = consumerSecret,
            token = requestToken, tokenSecret = requestSecret,
            extraOAuth = mapOf("oauth_verifier" to verifier),
            nonce = nonce(), timestampSeconds = timestampSeconds())
        return post(url, header) { body -> parseAccessToken(body)?.let { OAuthResult.Success(it) } ?: OAuthResult.Malformed }
    }

    private fun <T> post(url: String, authorization: String, parse: (String) -> OAuthResult<T>): OAuthResult<T> {
        val request = Request.Builder().url(url)
            .header("Authorization", authorization)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return try {
            calls.newCall(request).execute().use { response ->
                if (!response.isSuccessful) OAuthResult.Rejected
                else parse(response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            OAuthResult.NetworkFailure
        } catch (_: Exception) {
            OAuthResult.Malformed
        }
    }

    companion object {
        const val CALLBACK_SCHEME = "blaykie"
        const val CALLBACK_HOST = "oauth-callback"
        const val CALLBACK = "$CALLBACK_SCHEME://$CALLBACK_HOST"

        private fun randomNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Null when the build has no OAuth client credentials (fork or local build). */
internal fun zoteroOAuthClientOrNull(): ZoteroOAuthClient? {
    val key = BuildConfig.ZOTERO_OAUTH_CLIENT_KEY
    val secret = BuildConfig.ZOTERO_OAUTH_CLIENT_SECRET
    return if (key.isBlank() || secret.isBlank()) null else ZoteroOAuthClient(key, secret)
}
