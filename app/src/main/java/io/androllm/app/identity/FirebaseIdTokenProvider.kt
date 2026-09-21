package io.androllm.app.identity

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Phase 1 — Firebase ID token source for backend calls.
 *
 * Wraps `FirebaseAuth.currentUser.getIdToken()` in a suspend function so
 * [io.androllm.core.network.identity.IdentityApi] stays Firebase-free.
 * Returns null when signed out or when Firebase is unavailable (guest mode).
 */
@Singleton
class FirebaseIdTokenProvider @Inject constructor() {

    suspend fun getToken(forceRefresh: Boolean = false): String? {
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull() ?: return null
        val user = auth.currentUser ?: return null
        return try {
            suspendCancellableCoroutine { cont ->
                user.getIdToken(forceRefresh)
                    .addOnSuccessListener { result ->
                        cont.resume(result.token)
                    }
                    .addOnFailureListener { e ->
                        Timber.w(e, "[Identity] getIdToken failed")
                        cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            Timber.w(e, "[Identity] getIdToken threw")
            null
        }
    }

    fun isSignedIn(): Boolean =
        runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)
}
