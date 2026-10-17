package crypto

import model.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.HexFormat

fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid lowercase HEX" }

    return HexFormat.of().parseHex(this)
}

private val ed25519PublicPrefix = "302a300506032b6570032100".hexBytes()

fun PublicKey.rawHex(): PublicKeyHex {
    val bytes = encoded
    require(bytes.size == 44 && bytes.take(12).toByteArray().contentEquals(ed25519PublicPrefix))

    return PublicKeyHex(bytes.copyOfRange(12, 44).toHex())
}

fun PublicKeyHex.toJdkKey(): PublicKey = KeyFactory
    .getInstance("Ed25519")
    .generatePublic(X509EncodedKeySpec(ed25519PublicPrefix + hex.hexBytes()))

object JdkCryptography : Cryptography {
    override fun sha256(message: ByteArray): Hash256 = Hash256(MessageDigest.getInstance("SHA-256").digest(message).toHex())

    override fun verify(publicKey: PublicKeyHex, message: ByteArray, signature: SignatureHex): Boolean = try {
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey.toJdkKey())
            update(message)
            verify(signature.hex.hexBytes())
        }
    } catch (_: GeneralSecurityException) {
        false
    }
}

class JdkWalletSigner : WalletSigner {
    private val keys = mutableMapOf<Address, PrivateKey>()

    override fun createWallet(name: String): WalletInfo {
        require(name.isNotBlank())
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = pair.public.rawHex()
        val address = Address(JdkCryptography.sha256(publicKey.hex.hexBytes()).hex)
        keys[address] = pair.private

        return WalletInfo(name, publicKey, address)
    }

    override fun sign(address: Address, message: ByteArray): SignatureHex {
        val key = requireNotNull(keys[address]) { "Unknown signing wallet" }

        return Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            SignatureHex(sign().toHex())
        }
    }
}
