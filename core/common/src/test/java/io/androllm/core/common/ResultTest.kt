package io.androllm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the generic Result wrapper.
 */
class ResultTest {

    @Test
    fun `success holds data`() {
        val result = Result.success(42)
        assertTrue(result.isSuccess())
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `error holds exception`() {
        val exception = IllegalStateException("boom")
        val result: Result<Any> = Result.error(exception)
        assertTrue(result.isError())
        assertEquals(exception, (result as Result.Error).exception)
    }

    @Test
    fun `map transforms success only`() {
        val result = Result.success(2).map { it * 3 }
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun `map keeps error`() {
        val result = Result.error<String>("oops").map { it.length }
        assertTrue(result.isError())
    }

    @Test
    fun `runCatching wraps exceptions`() {
        val result = io.androllm.core.common.runCatching<Any> { throw IllegalArgumentException("bad") }
        assertTrue(result.isError())
    }

    @Test
    fun `runCatching wraps values`() {
        val result = runCatching { "value" }
        assertEquals("value", result.getOrNull())
    }

    @Test
    fun `getOrDefault falls back on error`() {
        val result = Result.error<Int>("oops")
        assertEquals(7, result.getOrDefault(7))
    }

    @Test
    fun `flatMap chains results`() {
        val result = Result.success(1).flatMap { Result.success(it + 1) }
        assertEquals(2, result.getOrNull())
    }

    @Test
    fun `onError is only called for errors`() {
        var called = false
        Result.success(1).onError { called = true }
        assertFalse(called)
    }
}
