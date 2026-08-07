package io.androllm.feature.profile

import android.content.Context
import android.net.Uri
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.BaseViewModel
import io.androllm.core.common.UiState
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.models.Model
import io.androllm.core.telemetry.TelemetryRepository
import javax.inject.Inject
import kotlin.coroutines.resume
import timber.log.Timber
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ViewModel for the profile screen: Firebase identity, real device/model stats,
 * favorites, cloud sync status, profile editing, avatar upload, and account
 * actions. The app remains fully functional in offline guest mode — Firebase is
 * never required.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val telemetryRepository: TelemetryRepository
) : BaseViewModel() {

    /** Firebase is optional — the app must keep working in offline guest mode. */
    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    private val storage: FirebaseStorage? = runCatching { FirebaseStorage.getInstance() }.getOrNull()
    private val credentialManager: CredentialManager? =
        runCatching { CredentialManager.create(appContext) }.getOrNull()

    private val _user = MutableStateFlow<UserIdentity?>(auth?.currentUser?.toIdentity())
    val user: StateFlow<UserIdentity?> = _user.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val _isAvatarUploading = MutableStateFlow(false)
    val isAvatarUploading: StateFlow<Boolean> = _isAvatarUploading.asStateFlow()

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val authListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { firebaseAuth ->
        _user.value = firebaseAuth.currentUser?.toIdentity()
        firebaseAuth.currentUser?.let { hydrateProfileFromFirestore(it) }
    }

    /** Logs ID-token refreshes so auth-session health is visible in the logs. */
    private val idTokenListener = com.google.firebase.auth.FirebaseAuth.IdTokenListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        Timber.d("[Auth] ID token refreshed for uid=${user?.uid ?: "none"}")
    }

    init {
        auth?.addAuthStateListener(authListener)
        auth?.addIdTokenListener(idTokenListener)
        // Sample real telemetry so the Tokens/sec and storage stats are live.
        telemetryRepository.startSampling()
    }

    override fun onCleared() {
        auth?.removeAuthStateListener(authListener)
        auth?.removeIdTokenListener(idTokenListener)
        telemetryRepository.stopSampling()
        super.onCleared()
    }

    val uiState: StateFlow<UiState<ProfileData>> = combine(
        conversationRepository.observeActive(),
        modelRepository.observeAllModels(),
        telemetryRepository.deviceMetrics,
        telemetryRepository.history
    ) { conversations, models, device, history ->
        UiState.Success(
            ProfileData(
                conversationCount = conversations.size,
                modelCount = models.count { it.isDownloaded },
                downloadCount = models.count { !it.isDownloaded },
                favoriteModels = models.filter { it.isFavorite },
                storageUsedBytes = device?.usedStorageBytes ?: 0L,
                storageTotalBytes = device?.totalStorageBytes ?: 0L,
                storageFreeBytes = device?.freeStorageBytes ?: 0L,
                tokensPerSecond = history.lastOrNull()?.tokensPerSecond ?: 0f,
                vulkanSupported = device?.isVulkanSupported == true
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState.Loading()
    )

    fun refresh() {
        telemetryRepository.refreshDeviceMetrics()
    }

    fun signOut() {
        viewModelScope.launch {
            Timber.i("[Auth] Logout — Firebase signOut requested")
            auth?.signOut()
            _user.value = null
            // Per the official Firebase docs, also clear the current user credential
            // state from all credential providers (Credential Manager) so the system
            // doesn't keep the account selected for the next sign-in.
            runCatching {
                credentialManager?.clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { e ->
                Timber.e(e, "[Auth] Failed to clear credential state after sign-out")
            }
        }
    }

    fun sendVerificationEmail() {
        viewModelScope.launch {
            val user = auth?.currentUser ?: return@launch
            user.sendEmailVerification()
                .addOnCompleteListener { task ->
                    _authMessage.value = if (task.isSuccessful) {
                        "Verification email sent"
                    } else {
                        "Failed to send: ${task.exception?.localizedMessage}"
                    }
                }
        }
    }

    /**
     * Updates the display name (Firebase profile + Firestore backup).
     */
    fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val user = auth?.currentUser ?: run {
            _authMessage.value = "Sign in to edit your profile"
            return
        }
        viewModelScope.launch {
            _isSavingProfile.value = true
            runCatching {
                user.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(trimmed).build()
                ).awaitResult()
                _user.value = auth?.currentUser?.toIdentity()
                syncFirestoreProfile()
            }.onSuccess {
                _authMessage.value = "Profile updated"
            }.onFailure { e ->
                _authMessage.value = "Update failed: ${e.localizedMessage}"
            }
            _isSavingProfile.value = false
        }
    }

    /**
     * Uploads an avatar image to Firebase Storage, then updates the Firebase
     * profile and the Firestore backup document.
     */
    fun uploadAvatar(uri: Uri) {
        val user = auth?.currentUser ?: run {
            _authMessage.value = "Sign in to upload an avatar"
            return
        }
        val storageRef = storage?.reference ?: run {
            _authMessage.value = "Storage unavailable"
            return
        }
        viewModelScope.launch {
            _isAvatarUploading.value = true
            runCatching {
                val ref = storageRef.child("avatars/${user.uid}.jpg")
                ref.putFile(uri).awaitResult()
                val downloadUrl = ref.downloadUrl.awaitResult().toString()
                user.updateProfile(
                    UserProfileChangeRequest.Builder().setPhotoUri(Uri.parse(downloadUrl)).build()
                ).awaitResult()
                _user.value = auth?.currentUser?.toIdentity()
                syncFirestoreProfile()
            }.onSuccess {
                _authMessage.value = "Avatar updated"
            }.onFailure { e ->
                _authMessage.value = "Upload failed: ${e.localizedMessage}"
            }
            _isAvatarUploading.value = false
        }
    }

    /**
     * Deletes the account. Reports the outcome through [onResult].
     */
    fun deleteAccount(onResult: (Boolean, String?) -> Unit) {
        val user = auth?.currentUser ?: run {
            onResult(false, "Not signed in")
            return
        }
        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                _user.value = null
                onResult(true, null)
            } else {
                onResult(false, task.exception?.localizedMessage ?: "Delete failed")
            }
        }
    }

    fun consumeAuthMessage() {
        _authMessage.value = null
    }

    /**
     * Offline-first: local Firebase profile is the source of truth; the Firestore
     * document only fills in gaps (e.g. display name set on another device).
     */
    private fun hydrateProfileFromFirestore(user: FirebaseUser) {
        val db = firestore ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val storedName = doc.getString("displayName")
                val storedPhoto = doc.getString("photoUrl")
                val current = _user.value ?: return@addOnSuccessListener
                _user.value = current.copy(
                    displayName = current.displayName ?: storedName,
                    photoUrl = current.photoUrl ?: storedPhoto
                )
            }
    }

    private fun syncFirestoreProfile() {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val data = hashMapOf<String, Any>(
            "displayName" to (user.displayName ?: ""),
            "email" to (user.email ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(user.uid).set(data)
    }
}

/**
 * UI snapshot of the Firebase identity (null-safe).
 */
data class UserIdentity(
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    val isEmailVerified: Boolean,
    val provider: String
) {
    val isGuest: Boolean get() = email.isNullOrBlank()
}

private fun FirebaseUser.toIdentity(): UserIdentity = UserIdentity(
    displayName = displayName,
    email = email,
    photoUrl = photoUrl?.toString(),
    isEmailVerified = isEmailVerified,
    provider = providerData.firstOrNull { it.providerId.isNotBlank() }?.providerId ?: ""
)

/**
 * Real stats shown on the profile screen.
 */
data class ProfileData(
    val conversationCount: Int = 0,
    val modelCount: Int = 0,
    val downloadCount: Int = 0,
    val favoriteModels: List<Model> = emptyList(),
    val storageUsedBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    val tokensPerSecond: Float = 0f,
    val vulkanSupported: Boolean = false
)

/**
 * Suspends until a Google Play Services task completes. Resuming a cancelled
 * continuation is a safe no-op, so a cancellation simply leaves a single
 * listener object that is garbage collected with the task.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resume(task.result)
        } else {
            cont.resumeWithException(task.exception ?: Exception("Task failed"))
        }
    }
}
