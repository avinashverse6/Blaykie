package org.blaykie.nativeapp.zotero

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ZoteroOAuthTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun parsesRequestTokenResponse() {
        val token = parseRequestToken("oauth_token=req123&oauth_token_secret=sec456&oauth_callback_confirmed=true")
        assertEquals(OAuthRequestToken("req123", "sec456"), token)
        assertNull(parseRequestToken("oauth_token=req123"))
        assertNull(parseRequestToken(""))
        assertFalse(token.toString().contains("sec456"))
    }

    @Test fun parsesAccessTokenResponseWhereSecretIsTheApiKey() {
        val access = parseAccessToken("oauth_token=tok&oauth_token_secret=APIKEY123&userID=12345&username=test%20user")
        assertEquals("APIKEY123", access?.apiKey)
        assertEquals(12345L, access?.userId)
        assertEquals("test user", access?.username)
        assertNull(parseAccessToken("oauth_token_secret=APIKEY123&username=no-id"))
        assertFalse(access.toString().contains("APIKEY123"))
    }

    @Test fun authorizeUrlRequestsReadOnlyAccess() {
        val client = ZoteroOAuthClient("key", "secret", OkHttpClient())
        assertEquals("https://www.zotero.org/oauth/authorize?oauth_token=req123" +
            "&name=Blaykie&library_access=1&notes_access=0&write_access=0&all_groups=read",
            client.authorizeUrl("req123"))
    }

    @Test fun callbackParsingAcceptsOnlyOwnSchemeAndHost() {
        val ok = parseOAuthCallback("blaykie://oauth-callback?oauth_token=req123&oauth_verifier=ver456")
        assertEquals(OAuthCallback("req123", "ver456"), ok)
        // Deny/absent verifier reaches us as a callback without one.
        assertEquals(OAuthCallback("req123", null),
            parseOAuthCallback("blaykie://oauth-callback?oauth_token=req123"))
        assertNull(parseOAuthCallback("https://oauth-callback?oauth_token=req123&oauth_verifier=ver456"))
        assertNull(parseOAuthCallback("blaykie://other-host?oauth_token=req123&oauth_verifier=ver456"))
        assertNull(parseOAuthCallback("not a uri ::"))
    }

    @Test fun requestAndAccessTokenCallsAreSigned() {
        server.enqueue(MockResponse().setBody("oauth_token=req123&oauth_token_secret=sec456&oauth_callback_confirmed=true"))
        server.enqueue(MockResponse().setBody("oauth_token=tok&oauth_token_secret=APIKEY123&userID=42&username=researcher"))
        val client = ZoteroOAuthClient("client-key", "client-secret", OkHttpClient(),
            baseUrl = server.url("/oauth/").toString(),
            nonce = { "abc123nonce" }, timestampSeconds = { 1700000000 })

        val request = client.requestToken()
        assertTrue(request is OAuthResult.Success)
        assertEquals("req123", (request as OAuthResult.Success).value.token)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/oauth/request", sent.path)
        assertEquals(blaykieUserAgent(), sent.getHeader("User-Agent"))
        assertEquals("*/*", sent.getHeader("Accept"))
        val authorization = sent.getHeader("Authorization")!!
        assertTrue(authorization.startsWith("OAuth "))
        assertTrue(authorization.contains("oauth_callback=\"blaykie%3A%2F%2Foauth-callback\""))
        assertTrue(authorization.contains("oauth_consumer_key=\"client-key\""))
        assertTrue(authorization.contains("oauth_signature=\""))

        val access = client.accessToken("req123", "sec456", "ver456")
        assertTrue(access is OAuthResult.Success)
        assertEquals("APIKEY123", (access as OAuthResult.Success).value.apiKey)
        assertEquals(42L, (access as OAuthResult.Success).value.userId)
        val sentAccess = server.takeRequest()
        assertEquals("/oauth/access", sentAccess.path)
        assertEquals(blaykieUserAgent(), sentAccess.getHeader("User-Agent"))
        assertEquals("*/*", sentAccess.getHeader("Accept"))
        val accessAuthorization = sentAccess.getHeader("Authorization")!!
        assertTrue(accessAuthorization.contains("oauth_token=\"req123\""))
        assertTrue(accessAuthorization.contains("oauth_verifier=\"ver456\""))
    }

    @Test fun userAgentCarriesVersionAndNoSecrets() {
        val agent = blaykieUserAgent()
        assertEquals("Blaykie/${org.blaykie.nativeapp.BuildConfig.VERSION_NAME} (Android; Zotero sign-in)", agent)
        assertTrue(agent.contains(org.blaykie.nativeapp.BuildConfig.VERSION_NAME))
        val key = org.blaykie.nativeapp.BuildConfig.ZOTERO_OAUTH_CLIENT_KEY
        val secret = org.blaykie.nativeapp.BuildConfig.ZOTERO_OAUTH_CLIENT_SECRET
        if (key.isNotEmpty()) assertFalse(agent.contains(key))
        if (secret.isNotEmpty()) assertFalse(agent.contains(secret))
    }

    @Test fun httpErrorKeepsOnlyStatusAndRedactedText() {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("oauth_token=req123&oauth_token_secret=sec456&error=Invalid consumer"))
        val client = ZoteroOAuthClient("client-key", "client-secret", OkHttpClient(),
            baseUrl = server.url("/oauth/").toString(), nonce = { "n" }, timestampSeconds = { 1 })
        val failure = client.requestToken() as OAuthResult.HttpFailure
        assertEquals(401, failure.status)
        assertFalse(failure.errorText.contains("req123"))
        assertFalse(failure.errorText.contains("sec456"))
        assertTrue(failure.errorText.contains("[redacted]"))
    }

    @Test fun errorTextRedactsTokensKeysAndUrls() {
        val body = "Failed for oauth_token=tok_abcdefgh12345678 secret=hunter2hunter2 " +
            "userID=12345 key=ABCDEF1234567890 see https://www.zotero.org/oauth/request?oauth_token=tok_abcdefgh12345678"
        val shown = oauthErrorText(body)
        assertFalse(shown.contains("tok_abcdefgh12345678"))
        assertFalse(shown.contains("hunter2hunter2"))
        assertFalse(shown.contains("12345"))
        assertFalse(shown.contains("ABCDEF1234567890"))
        assertFalse(shown.contains("oauth_token=tok"))
        assertTrue(shown.length <= 120)
    }

    @Test fun errorTextHidesHtmlBodiesAndCapsLength() {
        assertEquals("", oauthErrorText("<html><body>Unauthorized</body></html>"))
        assertEquals("", oauthErrorText(""))
        val long = oauthErrorText("word ".repeat(100))
        assertTrue(long.length <= 120)
    }

    @Test fun networkFailureKeepsOnlyExceptionClassName() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val client = ZoteroOAuthClient("client-key", "client-secret", OkHttpClient(),
            baseUrl = server.url("/oauth/").toString(), nonce = { "n" }, timestampSeconds = { 1 })
        val failure = client.requestToken() as OAuthResult.NetworkFailure
        assertTrue(failure.exceptionName.endsWith("Exception"))
        assertFalse(failure.exceptionName.contains("client-secret"))
    }
}
