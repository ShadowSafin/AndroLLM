package io.androllm.core.tools.tool.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactResolverTest {

    @Test
    fun `plain number with country code is a phone number`() {
        assertThat(ContactResolver.isPhoneNumber("+919876543210")).isTrue()
    }

    @Test
    fun `formatted number is detected and normalized`() {
        val raw = "+1 (555) 123-4567"
        assertThat(ContactResolver.isPhoneNumber(raw)).isTrue()
        assertThat(ContactResolver.normalize(raw)).isEqualTo("+15551234567")
    }

    @Test
    fun `bare local number is kept as digits`() {
        assertThat(ContactResolver.normalize("(555) 123-4567")).isEqualTo("5551234567")
    }

    @Test
    fun `contact name is not a phone number`() {
        assertThat(ContactResolver.isPhoneNumber("Mom")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("John Doe")).isFalse()
    }

    @Test
    fun `names containing a few digits are not phone numbers`() {
        assertThat(ContactResolver.isPhoneNumber("Room 404")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("404 Not Found")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("2 Fast 4 You")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("Mom2")).isFalse()
    }

    @Test
    fun `blank and junk inputs are not phone numbers`() {
        assertThat(ContactResolver.isPhoneNumber("")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("  ")).isFalse()
        assertThat(ContactResolver.isPhoneNumber("mom@sms")).isFalse()
    }

    @Test
    fun `normalize trims whitespace`() {
        assertThat(ContactResolver.normalize("  +49 30 123456  ")).isEqualTo("+4930123456")
    }
}
