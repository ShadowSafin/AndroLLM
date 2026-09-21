package io.androllm.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.network.identity.IdentityApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Phase 2 — Web Dashboard connector state.
 *
 * Explicit account linking only. This ViewModel never uploads usage data;
 * [IdentityApi.connect] flips the backend `web_connected` flag for the
 * verified Firebase UID and nothing else. Phase 3 sync must additionally
 * check `UserProfile.canSyncUsage()` before sending any event.
 *
 * Source of truth is the backend profile (`GET /me`); the DataStore copy is
 * a display cache so the card renders instantly and offline.
 */
data class WebDashboardState(
    val signedIn: Boolean = false,
    val backendConfigured: Boolean = false,
    val connected: Boolean = false,
    val connectedAt: String? = null,
    /** True once this session confirmed the state with the backend. */
    val isFresh: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val showConfirm: Boolean = false,
)

@HiltViewModel
class WebDashboardViewModel @Inject constructor(
    private val identityApi: IdentityApi,
    private val preferencesDataStore: PreferencesDataStore,
) : ViewModel() {

    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()

    private val _state = MutableStateFlow(
        WebDashboardState(backendConfigured = identityApi.isConfigured)
    )
    val state: StateFlow<WebDashboardState> = _state.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val signedIn = firebaseAuth.currentUser != null
        _state.update { it.copy(signedIn = signedIn, error = null, isFresh = false) }
        if (signedIn) refresh() else _state.update { it.copy(busy = false, showConfirm = false) }
    }

    init {
        // Instant/offline render from the local cache.
        viewModelScope.launch {
            combine(
                preferencesDataStore.webConnected,
                preferencesDataStore.webConnectedAt,
            ) { connected, at -> connected to at }
                .collect { (connected, at) ->
                    _state.update {
                        // Backend-fresh state wins over the cache within a session.
                        if (it.isFresh) it else it.copy(connected = connected, connectedAt = at)
                    }
                }
        }
        _state.update { it.copy(signedIn = runCatching { auth?.currentUser != null }.getOrDefault(false)) }
        auth?.addAuthStateListener(authListener)
        if (_state.value.signedIn) refresh()
    }

    override fun onCleared() {
        super.onCleared()
        auth?.removeAuthStateListener(authListener)
    }

    /** Reconcile with the backend profile. Safe to call on every settings open. */
    fun refresh() {
        if (!identityApi.isConfigured) return
        if (runCatching { auth?.currentUser == null }.getOrDefault(true)) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = identityApi.me()) {
                is IdentityApi.IdentityResult.Success -> {
                    preferencesDataStore.setWebConnection(
                        result.value.webConnected,
                        result.value.webConnectedAt,
                    )
                    _state.update {
                        it.copy(
                            connected = result.value.webConnected,
                            connectedAt = result.value.webConnectedAt,
                            isFresh = true,
                            busy = false,
                        )
                    }
                }
                is IdentityApi.IdentityResult.Unauthorized ->
                    _state.update { it.copy(busy = false, error = "Session expired — please sign in again.") }
                is IdentityApi.IdentityResult.Failure -> {
                    Timber.w("[WebDashboard] refresh failed: ${result.message}")
                    // Keep the cached state; surface a soft error only.
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Open the explicit-approval dialog. No network call happens here. */
    fun requestConnect() {
        val current = _state.value
        if (!current.signedIn || !current.backendConfigured || current.connected || current.busy) return
        _state.update { it.copy(showConfirm = true, error = null) }
    }

    fun dismissConfirm() {
        _state.update { it.copy(showConfirm = false) }
    }

    /** User confirmed — perform the one-time linking call. Uploads no usage data. */
    fun confirmConnect() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, showConfirm = false) }
            when (val result = identityApi.connect()) {
                is IdentityApi.IdentityResult.Success -> {
                    preferencesDataStore.setWebConnection(
                        result.value.webConnected,
                        result.value.webConnectedAt,
                    )
                    _state.update {
                        it.copy(
                            connected = result.value.webConnected,
                            connectedAt = result.value.webConnectedAt,
                            isFresh = true,
                            busy = false,
                        )
                    }
                }
                is IdentityApi.IdentityResult.Unauthorized ->
                    _state.update { it.copy(busy = false, error = "Session expired — please sign in again.") }
                is IdentityApi.IdentityResult.Failure -> {
                    Timber.w("[WebDashboard] connect failed: ${result.message}")
                    _state.update { it.copy(busy = false, error = "Couldn't connect. Check your connection and try again.") }
                }
            }
        }
    }

    /** Revoke linking. Idempotent; clears the local cache on success. */
    fun disconnect() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = identityApi.disconnect()) {
                is IdentityApi.IdentityResult.Success -> {
                    preferencesDataStore.setWebConnection(
                        result.value.webConnected,
                        result.value.webConnectedAt,
                    )
                    _state.update {
                        it.copy(
                            connected = result.value.webConnected,
                            connectedAt = result.value.webConnectedAt,
                            isFresh = true,
                            busy = false,
                        )
                    }
                }
                is IdentityApi.IdentityResult.Unauthorized ->
                    _state.update { it.copy(busy = false, error = "Session expired — please sign in again.") }
                is IdentityApi.IdentityResult.Failure -> {
                    Timber.w("[WebDashboard] disconnect failed: ${result.message}")
                    _state.update { it.copy(busy = false, error = "Couldn't disconnect. Check your connection and try again.") }
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
