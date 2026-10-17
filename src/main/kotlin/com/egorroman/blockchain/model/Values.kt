package com.egorroman.blockchain.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Address(val hex: String) {
    init { requireHex(hex, 64) }
}

@Serializable
@JvmInline
value class Hash256(val hex: String) {
    init { requireHex(hex, 64) }

    companion object { val ZERO = Hash256("0".repeat(64)) }
}

@Serializable
@JvmInline
value class PublicKeyHex(val hex: String) {
    init { requireHex(hex, 64) }
}

@Serializable
@JvmInline
value class SignatureHex(val hex: String) {
    init { requireHex(hex, 128) }
}

private fun requireHex(value: String, length: Int) {
    require(value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Expected $length lowercase HEX characters"
    }
}
