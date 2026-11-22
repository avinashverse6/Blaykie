package org.blaykie.nativeapp.oauthdiag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthDiagnosticsTest {

    @Test fun bodySnippetRedactsTokenValues() {
        val snippet = diagnosticBodySnippet(
            "oauth_token=tok_abcdefgh12345678&oauth_token_secret=sec_abcdefgh12345678 denied")
        assertFalse(snippet.contains("tok_abcdefgh12345678"))
        assertFalse(snippet.contains("sec_abcdefgh12345678"))
        assertTrue(snippet.length <= 120)
    }

    @Test fun clockOffsetUsesServerDate() {
        // Sun, 06 Nov 1994 08:49:37 GMT is epoch 784111777.
        assertEquals(37L, clockOffsetSeconds("Sun, 06 Nov 1994 08:49:37 GMT", 784111740L))
        assertNull(clockOffsetSeconds(null, 784111740L))
        assertNull(clockOffsetSeconds("not a date", 784111740L))
    }

    @Test fun reportShowsPresenceFlagsAndUrlWithoutValues() {
        val report = diagnosticReport(true, false, listOf("A: HTTP 403"), null)
        assertTrue(report.contains("Client key present: true"))
        assertTrue(report.contains("Client secret present: false"))
        assertTrue(report.contains("Request URL: https://www.zotero.org/oauth/request"))
        assertTrue(report.contains("Clock offset (server - device): unknown"))
        assertTrue(report.contains("A: HTTP 403"))
    }
}
