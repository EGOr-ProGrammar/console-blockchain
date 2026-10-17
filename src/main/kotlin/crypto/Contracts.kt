package crypto

import model.*
import kotlinx.serialization.json.JsonElement

/** Owns private keys; only public identity and signatures may leave this boundary. */
interface WalletSigner {
    fun createWallet(name: String): WalletInfo
    fun sign(address: Address, message: ByteArray): SignatureHex
}

/** SHA-256 hashes bytes; Ed25519 verifies the original message, never a prehash. */
interface Cryptography {
    fun sha256(message: ByteArray): Hash256
    fun verify(publicKey: PublicKeyHex, message: ByteArray, signature: SignatureHex): Boolean
}

/** Compact UTF-8 JSON, recursively sorted keys; ASCII strings and nonnegative Longs only. */
interface CanonicalEncoder {
    fun encode(value: JsonElement): ByteArray
}
