package org.blaykie.nativeapp.zotero

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal OAuth 1.0a (RFC 5849) HMAC-SHA1 signing, implemented directly so no
 * OAuth library is needed. Nothing here logs or stores credentials.
 */
internal object OAuth1 {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 percent-encoding as required by RFC 5849 section 3.6. */
    fun percentEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char in UNRESERVED) out.append(char)
            else out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xf])
        }
        return out.toString()
    }

    fun normalizedParameters(parameters: Map<String, String>): String =
        parameters.entries
            .map { percentEncode(it.key) to percentEncode(it.value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

    /** Scheme and host are lowercase and default ports are dropped (RFC 5849 section 3.4.1.2). */
    fun baseUrl(url: String): String {
        val uri = java.net.URI(url)
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
        return "$scheme://$host$port${uri.rawPath}"
    }

    fun signatureBaseString(method: String, url: String, parameters: Map<String, String>): String =
        listOf(method.uppercase(), percentEncode(baseUrl(url)), percentEncode(normalizedParameters(parameters)))
            .joinToString("&")

    fun hmacSha1(baseString: String, consumerSecret: String, tokenSecret: String): String {
        val key = percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        return base64(mac.doFinal(baseString.toByteArray(StandardCharsets.UTF_8)))
    }

    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(bytes: ByteArray): String {
        val out = StringBuilder()
        var index = 0
        while (index + 3 <= bytes.size) {
            val value = (bytes[index].toInt() and 0xff shl 16) or (bytes[index + 1].toInt() and 0xff shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            out.append(BASE64[value shr 18]).append(BASE64[value shr 12 and 63])
                .append(BASE64[value shr 6 and 63]).append(BASE64[value and 63])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val value = bytes[index].toInt() and 0xff shl 16
                out.append(BASE64[value shr 18]).append(BASE64[value shr 12 and 63]).append("==")
            }
            2 -> {
                val value = (bytes[index].toInt() and 0xff shl 16) or (bytes[index + 1].toInt() and 0xff shl 8)
                out.append(BASE64[value shr 18]).append(BASE64[value shr 12 and 63])
                    .append(BASE64[value shr 6 and 63]).append('=')
            }
        }
        return out.toString()
    }

    /**
     * Builds the Authorization header value. [parameters] holds query/body parameters that
     * must be signed alongside the oauth_* ones; oauth_signature itself is never signed.
     */
    fun authorizationHeader(method: String, url: String, parameters: Map<String, String> = emptyMap(),
                            consumerKey: String, consumerSecret: String,
                            token: String? = null, tokenSecret: String = "",
                            extraOAuth: Map<String, String> = emptyMap(),
                            nonce: String, timestampSeconds: Long): String {
        val oauth = linkedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestampSeconds.toString(),
            "oauth_version" to "1.0",
        )
        if (token != null) oauth["oauth_token"] = token
        oauth.putAll(extraOAuth)
        val signature = hmacSha1(signatureBaseString(method, url, parameters + oauth), consumerSecret, tokenSecret)
        val header = oauth + ("oauth_signature" to signature)
        return header.entries.sortedBy { it.key }
            .joinToString(", ", prefix = "OAuth ") { "${percentEncode(it.key)}=\"${percentEncode(it.value)}\"" }
    }
}
