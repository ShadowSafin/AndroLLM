package io.androllm.feature.onboarding

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

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
    fun `starts on first page`() = runTest {
        val vm = OnboardingViewModel(dataStore)
        assertEquals(0, vm.currentPage.value)
        assertEquals(5, vm.pageCount)
        assertFalse(vm.isLastPage)
    }

    @Test
    fun `next advances until the last page`() = runTest {
        val vm = OnboardingViewModel(dataStore)
        vm.next()
        assertEquals(1, vm.currentPage.value)
        vm.next()
        vm.next()
        vm.next()
        assertEquals(4, vm.currentPage.value)
        assertTrue(vm.isLastPage)
        // Clamped at the last page.
        vm.next()
        assertEquals(4, vm.currentPage.value)
    }

    @Test
    fun `setPage clamps to the valid range`() = runTest {
        val vm = OnboardingViewModel(dataStore)
        vm.setPage(2)
        assertEquals(2, vm.currentPage.value)
        vm.setPage(99)
        assertEquals(4, vm.currentPage.value)
        vm.setPage(-5)
        assertEquals(0, vm.currentPage.value)
    }

    @Test
    fun `back never goes below the first page`() = runTest {
        val vm = OnboardingViewModel(dataStore)
        vm.back()
        assertEquals(0, vm.currentPage.value)
    }

    @Test
    fun `complete persists the flag and invokes callback`() = runTest {
        val vm = OnboardingViewModel(dataStore)
        var done = false
        vm.complete { done = true }
        advanceUntilIdle()
        coVerify { dataStore.setOnboardingCompleted(true) }
        assertTrue(done)
    }
}
