package io.androllm.core.accessibility.planner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafetyClassifierTest {

    @Test
    fun `send message goal is risky`() {
        assertThat(SafetyClassifier.isRiskyGoal("Send a WhatsApp message to Mom")).isTrue()
    }

    @Test
    fun `benign search goal is not risky`() {
        assertThat(SafetyClassifier.isRiskyGoal("Search YouTube for Android 17")).isFalse()
    }

    @Test
    fun `send button tap requires confirmation`() {
        val click = PlannedAction.Click("Send")
        assertThat(SafetyClassifier.requiresConfirmation("Send a message", click)).isTrue()
    }

    @Test
    fun `search button tap does not require confirmation`() {
        val click = PlannedAction.Click("Search")
        assertThat(SafetyClassifier.requiresConfirmation("Search YouTube for Android 17", click)).isFalse()
    }

    @Test
    fun `delete button is risky even without risky goal`() {
        assertThat(SafetyClassifier.isRiskyTarget("Delete")).isTrue()
        assertThat(SafetyClassifier.requiresConfirmation("Clean up my files", PlannedAction.Click("Delete"))).isTrue()
    }

    @Test
    fun `book uber ride requires confirmation`() {
        assertThat(SafetyClassifier.isRiskyGoal("Book an Uber to the airport")).isTrue()
        assertThat(SafetyClassifier.requiresConfirmation("Book an Uber", PlannedAction.Click("Request ride"))).isTrue()
    }

    @Test
    fun `typing is never confirmed by the classifier`() {
        assertThat(SafetyClassifier.requiresConfirmation("Send a message", PlannedAction.Type("hi"))).isFalse()
    }
}
