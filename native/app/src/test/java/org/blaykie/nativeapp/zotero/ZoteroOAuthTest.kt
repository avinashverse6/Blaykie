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
        val accessAuthorization = sentAccess.getHeader("Authorization")!!
        assertTrue(accessAuthorization.contains("oauth_token=\"req123\""))
        assertTrue(accessAuthorization.contains("oauth_verifier=\"ver456\""))
    }

    @Test fun httpErrorIsRejectedNotParsed() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("oauth_token=req123"))
        val client = ZoteroOAuthClient("client-key", "client-secret", OkHttpClient(),
            baseUrl = server.url("/oauth/").toString(), nonce = { "n" }, timestampSeconds = { 1 })
        assertSame(OAuthResult.Rejected, client.requestToken())
    }
}
