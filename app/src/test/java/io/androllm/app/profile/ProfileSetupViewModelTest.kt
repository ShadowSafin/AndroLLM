package io.androllm.app.profile

import io.androllm.core.datastore.PreferencesDataStore
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: PreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save persists profile locally and completes`() = runTest {
        val vm = ProfileSetupViewModel(dataStore)
        var done = false
        vm.save(displayName = "Ada Lovelace", username = "ada", avatarIndex = 2, accentHex = "FFFF7043") { done = true }
        advanceUntilIdle()
        coVerify { dataStore.setDisplayName("Ada Lovelace") }
        coVerify { dataStore.setUsername("ada") }
        coVerify { dataStore.setAvatarIndex(2) }
        coVerify { dataStore.setAccentColor("FFFF7043") }
        coVerify { dataStore.setOnboardingCompleted(true) }
        assertTrue(done)
    }

    @Test
    fun `save trims whitespace from name and username`() = runTest {
        val vm = ProfileSetupViewModel(dataStore)
        vm.save(displayName = "  Ada  ", username = "  ada  ", avatarIndex = 0, accentHex = "FF38BDF8") {}
        advanceUntilIdle()
        coVerify { dataStore.setDisplayName("Ada") }
        coVerify { dataStore.setUsername("ada") }
    }
}
