package io.androllm.feature.setup

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionManager
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the first-launch "Let's set up AndroLLM" screen.
 *
 * All permission/access logic lives in [PermissionManager]; this ViewModel
 * only bridges it to the UI and persists the one-shot completion flag.
 */
@HiltViewModel
class PermissionSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    /** null while the persisted flag is loading. */
    private val _setupCompleted = MutableStateFlow<Boolean?>(null)
    val setupCompleted: StateFlow<Boolean?> = _setupCompleted.asStateFlow()

    /** All permission/access cards, in the recommended request order. */
    val handlers: List<PermissionHandler>
        get() = permissionManager.handlers

    init {
        viewModelScope.launch {
            _setupCompleted.value = preferencesDataStore.setupCompleted.first()
        }
    }

    /** Live state of [handler]; pass the hosting [Activity] to detect permanent denial. */
    fun status(handler: PermissionHandler, activity: Activity? = null): PermissionState =
        permissionManager.status(handler, activity)

    /** Runtime permissions to feed the system dialog, if any. */
    fun runtimePermissions(handler: PermissionHandler): List<String> =
        permissionManager.runtimePermissions(handler)

    /** Records the dialog request so permanent-denial detection knows the history. */
    fun onRequested(handler: PermissionHandler) = permissionManager.onPermissionRequested(handler)

    /** Opens the system screen (accessibility settings / app details / …). */
    fun openSettings(handler: PermissionHandler): Boolean = permissionManager.openSettings(handler)

    /**
     * Persists completion and hands over to the main app. Always callable —
     * optional permissions that were skipped simply stay off and can be
     * enabled later from Settings → Permissions & Access.
     */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            preferencesDataStore.setSetupCompleted(true)
            onDone()
        }
    }
}
