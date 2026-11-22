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
import org.blaykie.nativeapp.zotero.OAuthAccessToken
import org.blaykie.nativeapp.zotero.OAuthRequestToken
import org.blaykie.nativeapp.zotero.OAuthResult
import org.blaykie.nativeapp.zotero.ZoteroClient
import org.blaykie.nativeapp.zotero.ZoteroOAuthClient
import org.blaykie.nativeapp.zotero.parseOAuthCallback
import org.blaykie.nativeapp.zotero.zoteroOAuthClientOrNull

/** The five sign-in stages, each with its own friendly message and details label. */
internal enum class OAuthStage(val step: String, val message: String) {
    RequestToken("Step 1 (request token)", "Could not start the sign-in with Zotero. Please try again."),
    Browser("Step 2 (opening Zotero)", "Could not open your browser. Please try again."),
    Callback("Step 3 (callback)", "Sign-in did not finish. Please sign in again."),
    AccessToken("Step 4 (access token)", "Zotero did not finish the sign-in. Please try again."),
    KeyCheck("Step 5 (key check)", "Could not check the key with Zotero. Please try again."),
}

/** Secret-free one-line diagnosis: step, HTTP status or transport cause, redacted server text. */
internal fun oauthStageDetails(stage: OAuthStage, result: OAuthResult<*>): String = when (result) {
    is OAuthResult.HttpFailure -> "${stage.step}: HTTP ${result.status}" +
        if (result.errorText.isNotBlank()) " — ${result.errorText}" else ""
    is OAuthResult.NetworkFailure -> "${stage.step}: ${result.exceptionName}"
    else -> "${stage.step}: unreadable reply"
}

internal enum class AccountRoute { LOADING, FIRST_RUN, APP, KEY }
internal data class AccountSummary(val userId: Long, val lastFour: String, val permissions: KeyPermissions,
                                   val username: String? = null) {
    override fun toString() = "AccountSummary(redacted)"
}
internal data class AccountState(
    val route: AccountRoute = AccountRoute.LOADING,
    val account: AccountSummary? = null,
    val checking: Boolean = false,
    val warning: Boolean = false,
    val message: String = "",
    val details: String = "",
    val authorizeUrl: String? = null,
    /** Readable group count for the signed-in key; null until loaded (quietly, failures count as none). */
    val groupsCount: Int? = null,
) {
    override fun toString() = "AccountState(route=$route, account=$account, checking=$checking, warning=$warning)"
}
internal fun redactedKeySuffix(key: String): String = if (key.length > 4) key.takeLast(4) else ""

internal fun removalResult(state: AccountState, removed: Boolean): AccountState =
    if (removed) state.copy(route = AccountRoute.FIRST_RUN, checking = false, account = null, message = "", groupsCount = null)
    else state.copy(checking = false, message = "Could not remove the key. Please try again.")

internal class AccountController(private val store: AccountStore, private val logic: AccountLogic,
                                 private val oauth: ZoteroOAuthClient? = null) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = VerificationGate()
    private val mutable = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = mutable
    private var pending: AccountSecret? = null
    private var fromFirstRun = false
    // Kept in memory only: a process death simply means the user signs in again.
    private var pendingOAuth: OAuthRequestToken? = null
    private var lastCallback: String? = null

    val oauthAvailable: Boolean get() = oauth != null

    init {
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.read() to store.hasSkipped() }
            mutable.value = when (val read = result.first) {
                is AccountRead.Present -> AccountState(route = AccountRoute.APP, account = summary(read.account))
                is AccountRead.Unavailable -> AccountState(route = AccountRoute.FIRST_RUN, message = "Your saved key is unavailable. Please add it again.")
                is AccountRead.Missing -> AccountState(route = if (result.second) AccountRoute.APP else AccountRoute.FIRST_RUN)
            }
            if (mutable.value.account != null) loadgroups()
        }
    }
    private fun summary(secret: AccountSecret): AccountSummary = AccountSummary(secret.verified.userId,
        redactedKeySuffix(secret.key), secret.verified.permissions, secret.username)

    private var groupsLoadedForUserId: Long = -1
    /** Quiet, cached group-count load for Settings; any failure simply shows none. */
    private fun loadgroups() {
        val userId = mutable.value.account?.userId ?: return
        if (groupsLoadedForUserId == userId && mutable.value.groupsCount != null) return
        groupsLoadedForUserId = userId
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                when (val read = store.read()) {
                    is AccountRead.Present -> org.blaykie.nativeapp.zotero.ZoteroGroupsCache.fetch(
                        ZoteroClient(), read.account.verified.userId, read.account.key).size
                    else -> 0
                }
            }
            if (mutable.value.account?.userId == userId) mutable.value = mutable.value.copy(groupsCount = count)
        }
    }

    fun startOAuth() {
        val client = oauth ?: return
        if (mutable.value.checking || !gate.begin()) return
        mutable.value = mutable.value.copy(checking = true, message = "", details = "")
        scope.launch {
            val result = try { withContext(Dispatchers.IO) { client.requestToken() } } finally { gate.end() }
            when (result) {
                is OAuthResult.Success -> {
                    pendingOAuth = result.value
                    mutable.value = mutable.value.copy(checking = false, authorizeUrl = client.authorizeUrl(result.value.token))
                }
                else -> mutable.value = mutable.value.copy(checking = false,
                    message = OAuthStage.RequestToken.message,
                    details = oauthStageDetails(OAuthStage.RequestToken, result))
            }
        }
    }

    fun oauthBrowserOpened() {
        mutable.value = mutable.value.copy(authorizeUrl = null)
    }

    fun oauthBrowserFailed() {
        pendingOAuth = null
        mutable.value = mutable.value.copy(authorizeUrl = null, message = OAuthStage.Browser.message,
            details = "${OAuthStage.Browser.step}: no browser")
    }

    fun handleOAuthCallback(data: android.net.Uri?) {
        val url = data?.toString() ?: return
        if (url == lastCallback) return // Activity recreation redelivers the same intent.
        val callback = parseOAuthCallback(url) ?: return
        lastCallback = url
        val pendingToken = pendingOAuth
        if (pendingToken == null) {
            mutable.value = mutable.value.copy(message = "That sign-in link has expired. Please sign in again.",
                details = "${OAuthStage.Callback.step}: no pending sign-in")
            return
        }
        if (callback.token == null || callback.token != pendingToken.token) {
            pendingOAuth = null
            mutable.value = mutable.value.copy(message = "Sign-in did not match. Please sign in again.",
                details = "${OAuthStage.Callback.step}: token mismatch")
            return
        }
        val verifier = callback.verifier
        if (verifier.isNullOrBlank()) {
            pendingOAuth = null
            mutable.value = mutable.value.copy(message = "Sign-in was cancelled. You can try again whenever you are ready.",
                details = "${OAuthStage.Callback.step}: no oauth_verifier")
            return
        }
        val client = oauth ?: return
        if (mutable.value.checking || !gate.begin()) return
        pendingOAuth = null
        mutable.value = mutable.value.copy(checking = true, message = "", details = "")
        scope.launch {
            val exchanged = try { withContext(Dispatchers.IO) {
                client.accessToken(pendingToken.token, pendingToken.secret, verifier)
            } } finally { gate.end() }
            when (exchanged) {
                is OAuthResult.Success -> verifyOAuthKey(exchanged.value)
                else -> mutable.value = mutable.value.copy(checking = false,
                    message = OAuthStage.AccessToken.message,
                    details = oauthStageDetails(OAuthStage.AccessToken, exchanged))
            }
        }
    }

    private fun verifyOAuthKey(access: OAuthAccessToken) {
        if (!gate.begin()) return
        mutable.value = mutable.value.copy(checking = true)
        scope.launch {
            val result = try { withContext(Dispatchers.IO) { logic.verify(access.apiKey) } } finally { gate.end() }
            when (result) {
                is Verification.Failure -> mutable.value = mutable.value.copy(checking = false,
                    message = result.message,
                    details = result.details.ifBlank { "${OAuthStage.KeyCheck.step}: unreadable reply" })
                is Verification.Verified -> {
                    if (result.secret.verified.userId != access.userId) {
                        mutable.value = mutable.value.copy(checking = false,
                            message = "Sign-in returned a different account. Please sign in again.",
                            details = "${OAuthStage.KeyCheck.step}: different user")
                        return@launch
                    }
                    // OAuth only ever grants the read-only scopes Blaykie requested, so the
                    // broader-access warning used for manually pasted keys does not apply here.
                    pending = AccountSecret(access.apiKey, result.secret.verified, access.username)
                    savePending()
                }
            }
        }
    }
    fun openKey(fromFirst: Boolean) {
        fromFirstRun = fromFirst; pending = null
        mutable.value = mutable.value.copy(route = AccountRoute.KEY, warning = false, message = "")
    }
    fun cancelKey() {
        if (mutable.value.checking) return
        pending = null
        mutable.value = mutable.value.copy(route = if (fromFirstRun) AccountRoute.FIRST_RUN else AccountRoute.APP, warning = false, message = "")
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
                is Verification.Failure -> mutable.value = mutable.value.copy(checking = false, message = result.message, details = "")
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
            if (saved) loadgroups()
            mutable.value = if (saved) mutable.value.copy(checking = false, warning = false,
                route = AccountRoute.APP, account = summary(secret), message = "", details = "")
            else mutable.value.copy(checking = false, warning = false, message = "Could not save the key securely. Please try again.")
        }
    }
    fun remove() {
        if (mutable.value.checking) return
        mutable.value = mutable.value.copy(checking = true, message = "")
        scope.launch {
            val removed = withContext(Dispatchers.IO) { store.remove() }
            mutable.value = removalResult(mutable.value, removed)
        }
    }
    override fun onCleared() { pending = null; pendingOAuth = null; scope.cancel() }
    class Factory(private val store: AccountStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountController(store, AccountLogic(store, ZoteroClient()), zoteroOAuthClientOrNull()) as T
    }
}
