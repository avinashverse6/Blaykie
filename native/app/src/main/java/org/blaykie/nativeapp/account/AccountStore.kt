package org.blaykie.nativeapp.account

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.VerifiedKey

/** Implementations must not put plaintext into exceptions or logs. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
    fun removeKey()
}

@Serializable
private class AccountRecord(
    val key: String,
    val userId: Long,
    val personalLibrary: Boolean,
    val personalFiles: Boolean,
    val personalNotes: Boolean,
    val personalWrite: Boolean,
    val groupAccess: Boolean,
)

class AccountSecret(val key: String, val verified: VerifiedKey) {
    override fun toString() = "AccountSecret(redacted)"
}

sealed class AccountRead {
    class Present(val account: AccountSecret) : AccountRead()
    object Missing : AccountRead()
    object Unavailable : AccountRead()
    final override fun toString() = "AccountRead.${javaClass.simpleName}"
}

/** Key and metadata are encrypted together. Only the onboarding marker is plaintext. */
class AccountStore(private val root: File, private val cipher: SecretCipher) {
    private val accountFile = File(root, "account.enc")
    private val temporary = File(root, "account.enc.tmp")
    private val skippedFile = File(root, "account-skipped")
    private val json = Json { ignoreUnknownKeys = true }
    private var needsKeyReset = false

    @Synchronized fun read(): AccountRead {
        if (!accountFile.isFile) return AccountRead.Missing
        var plaintext: ByteArray? = null
        return try {
            val decrypted = cipher.decrypt(accountFile.readBytes())
            plaintext = decrypted
            val record = json.decodeFromString<AccountRecord>(decrypted.toString(Charsets.UTF_8))
            require(record.key.isNotBlank() && record.userId > 0)
            AccountRead.Present(AccountSecret(record.key, VerifiedKey(record.userId,
                KeyPermissions(record.personalLibrary, record.personalFiles, record.personalNotes, record.personalWrite, record.groupAccess))))
        } catch (_: Exception) { needsKeyReset = true; AccountRead.Unavailable }
        finally { plaintext?.fill(0) }
    }
    @Synchronized fun save(account: AccountSecret): Boolean {
        var plaintext: ByteArray? = null
        return try {
            val p = account.verified.permissions
            val record = AccountRecord(account.key, account.verified.userId, p.personalLibrary, p.personalFiles, p.personalNotes, p.personalWrite, p.groupAccess)
            val encoded = json.encodeToString(record).toByteArray(Charsets.UTF_8)
            plaintext = encoded
            if (needsKeyReset) cipher.removeKey()
            val encrypted = cipher.encrypt(encoded)
            root.mkdirs()
            temporary.outputStream().use { it.write(encrypted); it.fd.sync() }
            check(temporary.renameTo(accountFile))
            needsKeyReset = false
            skippedFile.delete()
            true
        } catch (_: Exception) { temporary.delete(); false }
        finally { plaintext?.fill(0) }
    }
    @Synchronized fun remove(): Boolean {
        val accountRemoved = !accountFile.exists() || accountFile.delete()
        val temporaryRemoved = !temporary.exists() || temporary.delete()
        val filesRemoved = accountRemoved && temporaryRemoved
        val keyRemoved = try { cipher.removeKey(); true } catch (_: Exception) { false }
        val skipRemoved = !skippedFile.exists() || skippedFile.delete()
        return filesRemoved && keyRemoved && skipRemoved
    }
    @Synchronized fun skip(): Boolean = try { root.mkdirs(); skippedFile.writeText("1"); true } catch (_: Exception) { false }
    @Synchronized fun hasSkipped(): Boolean = skippedFile.isFile
}
