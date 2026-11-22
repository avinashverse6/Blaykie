package org.blaykie.nativeapp.zotero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuth1Test {

    @Test fun percentEncodingFollowsRfc3986() {
        assertEquals("abcXYZ-._~019", OAuth1.percentEncode("abcXYZ-._~019"))
        assertEquals("%20", OAuth1.percentEncode(" "))
        assertEquals("%2B", OAuth1.percentEncode("+"))
        assertEquals("%2F", OAuth1.percentEncode("/"))
        assertEquals("%3A%3D%26", OAuth1.percentEncode(":=&"))
        assertEquals("%C3%A9", OAuth1.percentEncode("é"))
        assertEquals("Ladies%20%2B%20Gentlemen%2C", OAuth1.percentEncode("Ladies + Gentlemen,"))
    }

    @Test fun rfc5849ExampleSignature() {
        // RFC 5849 sections 1.2 and 3.4: the canonical HMAC-SHA1 example.
        val parameters = mapOf(
            "file" to "vacation.jpg",
            "size" to "original",
            "oauth_consumer_key" to "dpf43f3p2l4k3l03",
            "oauth_token" to "nnch734d00sl2jdk",
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to "1191242096",
            "oauth_nonce" to "kllo9940pd9333jh",
            "oauth_version" to "1.0",
        )
        val base = OAuth1.signatureBaseString("GET", "http://photos.example.net/photos", parameters)
        assertEquals("GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26" +
            "oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3Dkllo9940pd9333jh%26" +
            "oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26" +
            "oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal", base)
        assertEquals("tR3+Ty81lMeYAr/Fid0kMTYa/WM=", OAuth1.hmacSha1(base, "kd94hf93k423kf44", "pfkkdhi9sl3r4s00"))
    }

    @Test fun twitterExampleSignature() {
        // The worked example from Twitter's OAuth documentation (same algorithm Zotero uses).
        val header = OAuth1.authorizationHeader(
            "POST", "https://api.twitter.com/1/statuses/update.json",
            parameters = mapOf(
                "include_entities" to "true",
                "status" to "Hello Ladies + Gentlemen, a signed OAuth request!",
            ),
            consumerKey = "xvz1evFS4wEEPTGEFPHBog",
            consumerSecret = "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
            token = "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
            tokenSecret = "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
            nonce = "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
            timestampSeconds = 1318622958,
        )
        assertTrue(header.startsWith("OAuth "))
        assertTrue(header.contains("oauth_signature=\"tnnArxj06cWHq44gCs1OSKk%2FjLY%3D\""))
        assertTrue(header.contains("oauth_consumer_key=\"xvz1evFS4wEEPTGEFPHBog\""))
    }

    @Test fun defaultPortsAndCaseAreNormalized() {
        assertEquals("https://www.zotero.org/oauth/request",
            OAuth1.baseUrl("HTTPS://WWW.ZOTERO.ORG:443/oauth/request"))
        assertEquals("http://example.net:8080/x", OAuth1.baseUrl("http://example.net:8080/x"))
    }
}
