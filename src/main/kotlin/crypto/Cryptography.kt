package crypto

import model.*

/** SHA-256 hashes bytes; Ed25519 verifies the original message, never a prehash. */
interface Cryptography {
    fun sha256(message: ByteArray): Hash256
    fun verify(publicKey: PublicKeyHex, message: ByteArray, signature: SignatureHex): Boolean
}
