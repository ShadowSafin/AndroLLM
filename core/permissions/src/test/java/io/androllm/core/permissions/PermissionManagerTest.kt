package io.androllm.core.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the central permission manager. Handlers are fakes — the
 * Android grant state is controlled through [rawState] so the manager's
 * state/order/feature logic is what gets exercised.
 */
class PermissionManagerTest {

    private val context = mockk<Context>(relaxed = true).apply {
        // checkSelfPermission feeds ContextCompat/PermissionUtils — default to
        // DENIED so "not granted" paths are the norm in these tests.
        every { checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
    }

    @After
    fun tearDown() {
        unmockkStaticIfNeeded()
    }

    private fun unmockkStaticIfNeeded() {
        runCatching { io.mockk.unmockkStatic(ActivityCompat::class) }
    }

    // ── Ordering ───────────────────────────────────────────────────────────

    @Test
    fun `handlers are sorted in the recommended request order`() {
        val camera = FakeHandler(id = "camera")
        val sms = FakeHandler(id = "sms")
        val voice = FakeHandler(id = "voice_assistant")
        val unknown = FakeHandler(id = "zzz_unknown")

        val manager = PermissionManager(context, setOf(camera, sms, voice, unknown))

        assertEquals(
            listOf("voice_assistant", "sms", "camera", "zzz_unknown"),
            manager.handlers.map { it.id }
        )
    }

    // ── State mapping ──────────────────────────────────────────────────────

    @Test
    fun `feature-disabled handlers report NOT_REQUIRED even when denied`() {
        val location = FakeHandler(id = "location", featureEnabled = false, raw = PermissionState.DENIED)
        val manager = PermissionManager(context, setOf(location))

        assertEquals(PermissionState.NOT_REQUIRED, manager.status(location))
    }

    @Test
    fun `granted handlers pass through`() {
        val voice = FakeHandler(id = "voice_assistant", raw = PermissionState.GRANTED)
        val manager = PermissionManager(context, setOf(voice))

        assertEquals(PermissionState.GRANTED, manager.status(voice))
    }

    @Test
    fun `settings-access handlers pass through NEEDS_SETTINGS`() {
        val a11y = FakeHandler(id = "accessibility", raw = PermissionState.NEEDS_SETTINGS)
        val manager = PermissionManager(context, setOf(a11y))

        assertEquals(PermissionState.NEEDS_SETTINGS, manager.status(a11y))
    }

    @Test
    fun `denied before any request stays DENIED (try again)`() {
        val sms = FakeHandler(id = "sms", raw = PermissionState.DENIED, perms = listOf("fake.permission"))
        val manager = PermissionManager(context, setOf(sms))

        assertEquals(PermissionState.DENIED, manager.status(sms))
    }

    @Test
    fun `denied after request with rationale is still DENIED`() {
        mockkStatic(ActivityCompat::class)
        every { ActivityCompat.shouldShowRequestPermissionRationale(any(), any()) } returns true
        val sms = FakeHandler(id = "sms", raw = PermissionState.DENIED, perms = listOf("fake.permission"))
        val manager = PermissionManager(context, setOf(sms))
        manager.onPermissionRequested(sms)

        assertEquals(PermissionState.DENIED, manager.status(sms, mockk<Activity>(relaxed = true)))
    }

    @Test
    fun `denied after request with no rationale is PERMANENTLY_DENIED`() {
        mockkStatic(ActivityCompat::class)
        every { ActivityCompat.shouldShowRequestPermissionRationale(any(), any()) } returns false
        val sms = FakeHandler(id = "sms", raw = PermissionState.DENIED, perms = listOf("fake.permission"))
        val manager = PermissionManager(context, setOf(sms))
        manager.onPermissionRequested(sms)

        assertEquals(PermissionState.PERMANENTLY_DENIED, manager.status(sms, mockk<Activity>(relaxed = true)))
    }

    @Test
    fun `denied without an activity never escalates to permanent denial`() {
        mockkStatic(ActivityCompat::class)
        every { ActivityCompat.shouldShowRequestPermissionRationale(any(), any()) } returns false
        val sms = FakeHandler(id = "sms", raw = PermissionState.DENIED, perms = listOf("fake.permission"))
        val manager = PermissionManager(context, setOf(sms))
        manager.onPermissionRequested(sms)

        // No activity → cannot ask Android about the rationale → stay DENIED.
        assertEquals(PermissionState.DENIED, manager.status(sms))
    }

    // ── Feature → permission mapping ───────────────────────────────────────

    @Test
    fun `permissionsNeeded derives the concrete list from the feature map`() {
        val voice = FakeHandler(
            id = "voice_assistant",
            perms = listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS")
        )
        val manager = PermissionManager(context, setOf(voice))

        assertEquals(
            listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"),
            manager.permissionsNeeded(Feature.VOICE_ASSISTANT)
        )
    }

    @Test
    fun `enabled-features aggregation only includes enabled features`() {
        val voice = FakeHandler(
            id = "voice_assistant",
            featureEnabled = true,
            perms = listOf("android.permission.RECORD_AUDIO")
        )
        val location = FakeHandler(
            id = "location",
            featureEnabled = false,
            perms = listOf("android.permission.ACCESS_FINE_LOCATION")
        )
        val manager = PermissionManager(context, setOf(voice, location))

        // Location is not enabled → its permission must NOT be requested.
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            manager.permissionsNeededForEnabledFeatures()
        )
    }
}

/** Test double with fully controllable behavior — no Android calls. */
private class FakeHandler(
    override val id: String,
    private val featureEnabled: Boolean = true,
    private val raw: PermissionState = PermissionState.GRANTED,
    private val perms: List<String> = emptyList()
) : PermissionHandler {

    override val title = id
    override val description = "fake"
    override val explanation = "fake"
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = perms
    override fun isFeatureEnabled(context: Context): Boolean = featureEnabled
    override fun rawState(context: Context): PermissionState = raw
    override fun openSettings(context: Context): Boolean = true
}
