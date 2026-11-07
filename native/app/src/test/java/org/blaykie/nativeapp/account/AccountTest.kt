package org.blaykie.nativeapp.account

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.VerifiedKey
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountTest {
    @get:Rule val temp = TemporaryFolder()
    private val secret = "sample-secret-zotero-api-key"
    private val permissions = KeyPermissions(true, false, true, false, false)
    private val verified = VerifiedKey(123, permissions)

    private class FakeCipher : SecretCipher {
        var material: ByteArray? = ByteArray(32) { (it + 1).toByte() }
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val key = material ?: ByteArray(32) { (it + 1).toByte() }.also { material = it }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            val key = material ?: throw IllegalStateException("Keystore missing")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
        override fun removeKey() { material = null }
    }
    @Test fun encryptedRoundTripKeepsPlaintextOutOfFile() {
        val cipher = FakeCipher(); val store = AccountStore(temp.root, cipher)
        assertTrue(store.save(AccountSecret(secret, verified)))
        assertFalse(File(temp.root, "account.enc").readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val result = store.read() as AccountRead.Present
        val restored = result.account
        assertFalse(result.toString().contains(secret)); assertFalse(restored.toString().contains(secret))
        val summary = AccountSummary(restored.verified.userId, restored.key.takeLast(4), restored.verified.permissions)
        assertFalse(AccountState(account = summary).toString().contains(secret))
        assertEquals(secret, restored.key); assertEquals(123L, restored.verified.userId); assertEquals(permissions, restored.verified.permissions)
    }
    @Test fun removeWipesCiphertextAndKeystoreEntry() {
        val cipher = FakeCipher(); val store = AccountStore(temp.root, cipher)
        assertTrue(store.save(AccountSecret(secret, verified))); assertTrue(store.remove())
        assertNull(cipher.material); assertFalse(File(temp.root, "account.enc").exists()); assertSame(AccountRead.Missing, store.read())
        val previous = AccountState(route = AccountRoute.APP, account = AccountSummary(123, redactedKeySuffix(secret), permissions), checking = true)
        val after = removalResult(previous, true)
        assertEquals(AccountRoute.FIRST_RUN, after.route); assertNull(after.account); assertFalse(after.checking)
        val failed = removalResult(previous, false)
        assertEquals(AccountRoute.APP, failed.route); assertEquals(previous.account, failed.account); assertFalse(failed.checking)
    }
    @Test fun displayedKeySuffixNeverExposesShortKeysOrMoreThanFourCharacters() {
        assertEquals("", redactedKeySuffix(""))
        assertEquals("", redactedKeySuffix("abc"))
        assertEquals("", redactedKeySuffix("abcd"))
        assertEquals("bcde", redactedKeySuffix("abcde"))
        assertEquals(secret.takeLast(4), redactedKeySuffix(secret))
        val summary = AccountSummary(123, redactedKeySuffix(secret), permissions)
        assertFalse(summary.toString().contains(secret))
        assertFalse(AccountState(account = summary).toString().contains(secret))
    }
    @Test fun missingOrInvalidKeystoreIsUnavailableAndCanBeReplaced() {
        val cipher = FakeCipher(); val store = AccountStore(temp.root, cipher)
        assertTrue(store.save(AccountSecret(secret, verified)))
        cipher.material = null
        assertSame(AccountRead.Unavailable, store.read())
        assertTrue(store.save(AccountSecret("replacement-key", verified)))
        assertEquals("replacement-key", (store.read() as AccountRead.Present).account.key)
        cipher.material = ByteArray(32) { 7 }
        assertSame(AccountRead.Unavailable, store.read())
    }
    @Test fun skipMarkerDoesNotStoreTheKeyAndIsClearedOnSave() {
        val store = AccountStore(temp.root, FakeCipher())
        assertTrue(store.skip()); assertTrue(store.hasSkipped()); assertSame(AccountRead.Missing, store.read())
        assertTrue(store.save(AccountSecret(secret, verified))); assertFalse(store.hasSkipped())
    }
    @Test fun decisionsWarnOnlyForWriteOrGroups() {
        assertFalse(needsPermissionWarning(permissions))
        assertTrue(needsPermissionWarning(permissions.copy(personalWrite = true)))
        assertTrue(needsPermissionWarning(permissions.copy(groupAccess = true)))
        assertFalse(needsPermissionWarning(permissions.copy(personalFiles = true)))
    }
    @Test fun gateIgnoresRepeatedVerification() {
        val gate = VerificationGate()
        assertTrue(gate.begin()); assertFalse(gate.begin()); gate.end(); assertTrue(gate.begin()); gate.end()
    }
    @Test fun verificationAndErrorsUseMockServerAndRedactSecrets() {
        val server = MockWebServer(); server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            val logic = AccountLogic(AccountStore(temp.root, FakeCipher()), ZoteroClient.forTesting(client))
            server.enqueue(MockResponse().setBody("""{"userID":123,"access":{"user":{"library":true,"write":true},"groups":{"all":{"library":true}}}}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody(secret))
            repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")) }
            val accepted = logic.verify(secret) as Verification.Verified
            assertTrue(accepted.needsWarning); assertEquals(123L, accepted.secret.verified.userId)
            assertEquals("/keys/current", server.takeRequest().path)
            val invalid = logic.verify(secret) as Verification.Failure
            assertTrue(invalid.message.contains("Invalid"))
            val limited = logic.verify(secret) as Verification.Failure
            assertTrue(limited.message.contains("rate limit"))
            for (value in listOf(accepted.toString(), accepted.secret.toString(), invalid.toString(), invalid.message, limited.message)) {
                assertFalse(value.contains(secret))
            }
        } finally { server.shutdown() }
    }
    @Test fun networkAndStorageFailuresNeverExposeTheKey() {
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalArgumentException(secret)
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalArgumentException(secret)
            override fun removeKey() { throw IllegalArgumentException(secret) }
        }
        val store = AccountStore(temp.root, cipher)
        assertFalse(store.save(AccountSecret(secret, verified)))
        val calls = OkHttpClient.Builder().addInterceptor { throw IOException(secret) }.build()
        val logic = AccountLogic(store, ZoteroClient.forTesting(calls))
        val failure = logic.verify(secret) as Verification.Failure
        assertTrue(failure.message.contains("Network"))
        assertFalse(failure.toString().contains(secret)); assertFalse(failure.message.contains(secret))
        assertFalse(AccountSecret(secret, verified).toString().contains(secret))
    }
}
