package org.blaykie.nativeapp.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.blaykie.nativeapp.zotero.KeyPermissions
import org.blaykie.nativeapp.zotero.ZoteroClient

internal enum class AccountRoute { LOADING, FIRST_RUN, APP, ACCOUNT, KEY }
internal data class AccountSummary(val userId: Long, val lastFour: String, val permissions: KeyPermissions) {
    override fun toString() = "AccountSummary(redacted)"
}
internal data class AccountState(
    val route: AccountRoute = AccountRoute.LOADING,
    val account: AccountSummary? = null,
    val checking: Boolean = false,
    val warning: Boolean = false,
    val message: String = "",
)
internal class AccountController(private val store: AccountStore, private val logic: AccountLogic) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = VerificationGate()
    private val mutable = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = mutable
    private var pending: AccountSecret? = null
    private var fromFirstRun = false

    init {
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.read() to store.hasSkipped() }
            mutable.value = when (val read = result.first) {
                is AccountRead.Present -> AccountState(route = AccountRoute.APP, account = summary(read.account))
                is AccountRead.Unavailable -> AccountState(route = AccountRoute.FIRST_RUN, message = "Your saved key is unavailable. Please add it again.")
                is AccountRead.Missing -> AccountState(route = if (result.second) AccountRoute.APP else AccountRoute.FIRST_RUN)
            }
        }
    }
    private fun summary(secret: AccountSecret): AccountSummary = AccountSummary(secret.verified.userId,
        if (secret.key.length > 4) secret.key.takeLast(4) else "", secret.verified.permissions)
    fun openAccount() { mutable.value = mutable.value.copy(route = AccountRoute.ACCOUNT, message = "") }
    fun closeAccount() { mutable.value = mutable.value.copy(route = AccountRoute.APP, message = "") }
    fun openKey(fromFirst: Boolean) {
        fromFirstRun = fromFirst; pending = null
        mutable.value = mutable.value.copy(route = AccountRoute.KEY, warning = false, message = "")
    }
    fun cancelKey() {
        if (mutable.value.checking) return
        pending = null
        mutable.value = mutable.value.copy(route = if (fromFirstRun) AccountRoute.FIRST_RUN else AccountRoute.ACCOUNT, warning = false, message = "")
    }
    fun skip() {
        scope.launch {
            if (withContext(Dispatchers.IO) { store.skip() }) mutable.value = mutable.value.copy(route = AccountRoute.APP, message = "")
            else mutable.value = mutable.value.copy(message = "Could not save your choice. Please try again.")
        }
    }
    fun verify(value: String) {
        if (mutable.value.checking || mutable.value.warning) return
        val key = value.trim()
        if (key.isEmpty()) { mutable.value = mutable.value.copy(message = "Paste your Zotero API key."); return }
        if (!gate.begin()) return
        mutable.value = mutable.value.copy(checking = true, message = "")
        scope.launch {
            val result = try { withContext(Dispatchers.IO) { logic.verify(key) } } finally { gate.end() }
            when (result) {
                is Verification.Failure -> mutable.value = mutable.value.copy(checking = false, message = result.message)
                is Verification.Verified -> {
                    pending = result.secret
                    if (result.needsWarning) mutable.value = mutable.value.copy(checking = false, warning = true)
                    else savePending()
                }
            }
        }
    }
    fun useAnyway() { if (pending != null && !mutable.value.checking) savePending() }
    private fun savePending() {
        val secret = pending ?: return
        mutable.value = mutable.value.copy(checking = true)
        scope.launch {
            val saved = withContext(Dispatchers.IO) { logic.save(secret) }
            pending = null
            mutable.value = if (saved) mutable.value.copy(checking = false, warning = false,
                route = if (fromFirstRun) AccountRoute.APP else AccountRoute.ACCOUNT, account = summary(secret), message = "")
            else mutable.value.copy(checking = false, warning = false, message = "Could not save the key securely. Please try again.")
        }
    }
    fun remove() {
        if (mutable.value.checking) return
        mutable.value = mutable.value.copy(checking = true, message = "")
        scope.launch {
            val removed = withContext(Dispatchers.IO) { store.remove() }
            mutable.value = if (removed) mutable.value.copy(checking = false, account = null, message = "Key removed from this phone.")
            else mutable.value.copy(checking = false, message = "Could not remove the key. Please try again.")
        }
    }
    override fun onCleared() { pending = null; scope.cancel() }
    class Factory(private val store: AccountStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountController(store, AccountLogic(store, ZoteroClient())) as T
    }
}
