package io.androllm.feature.setup

import android.content.Context
import io.androllm.core.datastore.PreferencesDataStore
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionManager
import io.androllm.core.permissions.PermissionState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: PreferencesDataStore
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads setupCompleted from the store`() = runTest {
        every { dataStore.setupCompleted } returns flowOf(false)
        val vm = PermissionSetupViewModel(context, permissionManager, dataStore)
        advanceUntilIdle()
        assertEquals(false, vm.setupCompleted.value)
    }

    @Test
    fun `already-completed setup stays completed`() = runTest {
        every { dataStore.setupCompleted } returns flowOf(true)
        val vm = PermissionSetupViewModel(context, permissionManager, dataStore)
        advanceUntilIdle()
        assertEquals(true, vm.setupCompleted.value)
    }

    @Test
    fun `finish persists completion and invokes callback`() = runTest {
        every { dataStore.setupCompleted } returns flowOf(false)
        val vm = PermissionSetupViewModel(context, permissionManager, dataStore)
        var done = false
        vm.finish { done = true }
        advanceUntilIdle()
        coVerify { dataStore.setSetupCompleted(true) }
        assertTrue(done)
    }

    @Test
    fun `status delegates to the permission manager`() = runTest {
        every { dataStore.setupCompleted } returns flowOf(false)
        val handler = FakeHandler()
        every { permissionManager.status(handler, null) } returns PermissionState.GRANTED
        val vm = PermissionSetupViewModel(context, permissionManager, dataStore)
        advanceUntilIdle()
        assertEquals(PermissionState.GRANTED, vm.status(handler))
    }

    @Test
    fun `handlers come from the permission manager in order`() = runTest {
        every { dataStore.setupCompleted } returns flowOf(false)
        val handler = FakeHandler()
        every { permissionManager.handlers } returns listOf(handler)
        val vm = PermissionSetupViewModel(context, permissionManager, dataStore)
        advanceUntilIdle()
        assertEquals(listOf(handler), vm.handlers)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsAccessViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `status delegates to the permission manager`() = runTest {
        val handler = FakeHandler()
        every { permissionManager.status(handler, null) } returns PermissionState.NEEDS_SETTINGS
        val vm = PermissionsAccessViewModel(context, permissionManager)
        assertEquals(PermissionState.NEEDS_SETTINGS, vm.status(handler))
    }

    @Test
    fun `openSettings delegates to the permission manager`() = runTest {
        val handler = FakeHandler()
        every { permissionManager.openSettings(handler) } returns true
        val vm = PermissionsAccessViewModel(context, permissionManager)
        assertTrue(vm.openSettings(handler))
    }
}

/** Minimal handler double — never touches Android APIs. */
private class FakeHandler : PermissionHandler {
    override val id = "fake"
    override val title = "Fake"
    override val description = "fake"
    override val explanation = "fake"
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true
    override fun rawState(context: Context): PermissionState = PermissionState.GRANTED
    override fun openSettings(context: Context): Boolean = true
}
