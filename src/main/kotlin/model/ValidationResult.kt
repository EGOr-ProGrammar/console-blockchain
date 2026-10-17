package model

import kotlinx.serialization.Serializable

@Serializable
data class ValidationError(val code: String, val message: String, val transactionIndex: Int? = null, val blockIndex: Long? = null)

@Serializable
sealed interface ValidationResult {
    @Serializable
    data object Valid : ValidationResult
    @Serializable
    data class Invalid(val error: ValidationError) : ValidationResult
}

sealed interface BlockValidation {
    data class Valid(val state: LedgerSnapshot) : BlockValidation
    data class Invalid(val error: ValidationError) : BlockValidation
}
