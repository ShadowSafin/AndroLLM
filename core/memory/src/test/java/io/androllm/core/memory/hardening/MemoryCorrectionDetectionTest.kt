package io.androllm.core.memory.hardening

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemoryCorrectionDetectionTest {

    private val helper = MemoryHardeningHelper()

    @Test
    fun `name correction is contradictory`() {
        assertThat(helper.isContradictory("User name is Alice", "User name is Bob")).isTrue()
    }

    @Test
    fun `location correction is contradictory`() {
        assertThat(helper.isContradictory("User lives in Tokyo", "User lives in Osaka")).isTrue()
    }

    @Test
    fun `same fact is not contradictory`() {
        assertThat(helper.isContradictory("User prefers dark mode", "User prefers dark mode")).isFalse()
    }

    @Test
    fun `unrelated facts are not contradictory`() {
        assertThat(helper.isContradictory("User prefers dark mode", "User lives in Tokyo")).isFalse()
    }

    @Test
    fun `preference value change is contradictory`() {
        assertThat(helper.isContradictory("User prefers short prompts", "User prefers long prompts")).isTrue()
    }
}
