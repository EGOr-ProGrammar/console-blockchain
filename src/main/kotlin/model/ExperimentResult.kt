package model

import kotlinx.serialization.Serializable

@Serializable
data class ExperimentResult(val name: String, val expected: String, val actual: String, val passed: Boolean)
