package io.androllm.core.tools.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentVariableStoreTest {

    private val store = AgentVariableStore()

    @Test
    fun `beginTurn resets the variables for the scope`() {
        store.beginTurn("conv-1")
        store.set("weather", "rain")
        assertThat(store.get("weather")).isEqualTo("rain")

        store.beginTurn("conv-1")
        assertThat(store.get("weather")).isNull()
        assertThat(store.snapshot()).isEmpty()
    }

    @Test
    fun `variables are isolated per conversation`() {
        store.beginTurn("conv-1")
        store.set("search", "results-a")

        store.beginTurn("conv-2")
        assertThat(store.get("search")).isNull()
        store.set("search", "results-b")

        // Back to the first scope: values persist until its next beginTurn.
        store.beginTurn("conv-1")
        assertThat(store.get("search")).isNull() // beginTurn cleared it
    }

    @Test
    fun `blank conversation uses the default scope`() {
        store.beginTurn("")
        store.set("battery", "80%")
        assertThat(store.get("battery")).isEqualTo("80%")
        assertThat(store.snapshot()).containsEntry("battery", "80%")
    }

    @Test
    fun `keys are trimmed and remove deletes`() {
        store.beginTurn("c")
        store.set(" key ", " value ")
        assertThat(store.get("key")).isEqualTo("value")
        store.remove("key")
        assertThat(store.get("key")).isNull()
    }
}
