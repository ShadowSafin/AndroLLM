package io.androllm.core.tools.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomationSettingsTest {

    @Test
    fun `tool calling is enabled by default - the pipeline must never start off`() {
        val defaults = AutomationSettings()
        assertThat(defaults.toolCallingEnabled).isTrue()
        assertThat(defaults.isToolEnabled("get_weather")).isTrue()
    }

    @Test
    fun `master switch gates everything`() {
        val off = AutomationSettings(toolCallingEnabled = false)
        assertThat(off.isToolEnabled("get_weather")).isFalse()

        val on = AutomationSettings(toolCallingEnabled = true)
        assertThat(on.isToolEnabled("get_weather")).isTrue()
    }

    @Test
    fun `disabledTools block specific tools`() {
        val settings = AutomationSettings(
            toolCallingEnabled = true,
            disabledTools = setOf("send_sms")
        )
        assertThat(settings.isToolEnabled("send_sms")).isFalse()
        assertThat(settings.isToolEnabled("get_weather")).isTrue()
    }

    @Test
    fun `confirmation modes gate correctly`() {
        val highRisk = AutomationSettings(confirmationMode = ConfirmationMode.HIGH_RISK)
        assertThat(highRisk.shouldConfirm(requiresConfirmation = true)).isTrue()
        assertThat(highRisk.shouldConfirm(requiresConfirmation = false)).isFalse()

        val always = AutomationSettings(confirmationMode = ConfirmationMode.ALWAYS)
        assertThat(always.shouldConfirm(requiresConfirmation = false)).isTrue()

        val never = AutomationSettings(confirmationMode = ConfirmationMode.NEVER)
        assertThat(never.shouldConfirm(requiresConfirmation = true)).isFalse()
    }
}
