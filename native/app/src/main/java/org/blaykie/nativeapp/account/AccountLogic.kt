package org.blaykie.nativeapp.account

import java.util.concurrent.atomic.AtomicBoolean
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroResult

fun needsPermissionWarning(permissions: KeyPermissions): Boolean = permissions.personalWrite || permissions.groupAccess

/** Used by the UI and JVM tests; it never throws server or crypto messages to callers. */
class AccountLogic(private val store: AccountStore, private val client: ZoteroClient) {
    fun verify(key: String): Verification = try {
        when (val result = client.verifyKey(key)) {
            is ZoteroResult.Success -> Verification.Verified(AccountSecret(key, result.value), needsPermissionWarning(result.value.permissions))
            is ZoteroResult.InvalidKey -> Verification.Failure("Invalid key or no access to this library.")
            is ZoteroResult.NetworkFailure -> Verification.Failure("Network error. Check your internet connection.")
            is ZoteroResult.RateLimited -> Verification.Failure("Zotero rate limit reached. Please try again later.")
            else -> Verification.Failure("Could not verify the key. Please try again.")
        }
    } catch (_: Exception) { Verification.Failure("Could not verify the key. Please try again.") }
    fun save(secret: AccountSecret) = store.save(secret)
}
sealed class Verification {
    class Verified(val secret: AccountSecret, val needsWarning: Boolean) : Verification()
    class Failure(val message: String) : Verification()
    final override fun toString() = "Verification.${javaClass.simpleName}"
}
class VerificationGate {
    private val busy = AtomicBoolean(false)
    fun begin(): Boolean = busy.compareAndSet(false, true)
    fun end() { busy.set(false) }
}
