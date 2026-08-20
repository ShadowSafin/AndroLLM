package io.androllm.core.tools.validation

/**
 * Result of validating a single tool call against its JSON schema and registry entry.
 * Never exposed to the user — internal pipeline signal.
 */
sealed interface ValidationResult {

    data object Valid : ValidationResult

    data class Invalid(
        val errors: List<String>,
        /** True when the failure is due to formatting (retryable), false for unknown tool (not retryable) */
        val retryable: Boolean = true
    ) : ValidationResult {
        constructor(error: String, retryable: Boolean = true) : this(listOf(error), retryable)
        val firstError: String get() = errors.firstOrNull() ?: "Validation failed"
    }

    val isValid: Boolean get() = this is Valid
    val isInvalid: Boolean get() = this is Invalid

    fun errorMessage(): String? = (this as? Invalid)?.errors?.joinToString("; ")
}
