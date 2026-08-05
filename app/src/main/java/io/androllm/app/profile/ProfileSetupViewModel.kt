package io.androllm.app.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.datastore.PreferencesDataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * First-run profile creation: display name, optional username, avatar preset
 * and accent color. Local (DataStore) writes are the source of truth and always
 * succeed; the Firebase profile + Firestore document update is best-effort and
 * never blocks entering the app.
 */
@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    /** Firebase is optional — profile setup must work offline too. */
    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Persists the chosen profile locally, mirrors it to Firebase best-effort,
     * then invokes [onDone].
     */
    fun save(
        displayName: String,
        username: String,
        avatarIndex: Int,
        accentHex: String,
        onDone: () -> Unit
    ) {
        val name = displayName.trim()
        val handle = username.trim()
        viewModelScope.launch {
            _isSaving.value = true
            // Local first — always succeeds.
            preferencesDataStore.setDisplayName(name)
            preferencesDataStore.setUsername(handle)
            preferencesDataStore.setAvatarIndex(avatarIndex)
            preferencesDataStore.setAccentColor(accentHex)
            preferencesDataStore.setOnboardingCompleted(true)

            // Cloud best-effort, never blocking.
            runCatching {
                val user = auth?.currentUser ?: return@runCatching
                user.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name).build()
                )
                val uid = user.uid
                firestore?.collection("users")?.document(uid)?.set(
                    hashMapOf(
                        "displayName" to name,
                        "username" to handle,
                        "avatarIndex" to avatarIndex,
                        "accentColor" to accentHex,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            _isSaving.value = false
            onDone()
        }
    }
}
