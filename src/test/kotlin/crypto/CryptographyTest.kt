package crypto

import model.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class CryptographyTest {
    @Test
    fun `canonical JSON sorts nested objects and preserves arrays and ASCII escapes`() {
        val input = Json.parseToJsonElement("""{"z":[2,{"b":"line\nquote\"","a":0}],"a":"ASCII"}""")
        assertEquals("""{"a":"ASCII","z":[2,{"a":0,"b":"line\nquote\""}]}""", CanonicalJson.encode(input).decodeToString())
    }

    @Test
    fun `canonical signing domain rejects null boolean floating negative overflow and nonASCII`() {
        for (input in listOf("null", "true", "false", "-1", "1.0", "1e2", "9223372036854775808", "\"Ж\"", "{\"Ж\":0}")) {
            assertFailsWith<IllegalArgumentException>(input) { CanonicalJson.encode(Json.parseToJsonElement(input)) }
        }
        assertEquals("9223372036854775807", CanonicalJson.encode(JsonPrimitive(Long.MAX_VALUE)).decodeToString())
    }

    @Test
    fun `hex round trip handles signed bytes and rejects malformed text`() {
        val bytes = byteArrayOf(0, 15, 16, 127, -128, -1)
        assertEquals("000f107f80ff", bytes.toHex())
        assertContentEquals(bytes, "000f107f80ff".hexBytes())
        for (invalid in listOf("f", "0x00", "GG", "AA", " 00")) {
            assertFailsWith<IllegalArgumentException> { invalid.hexBytes() }
        }
    }

    @Test
    fun `SHA256 matches standard empty and abc vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", JdkCryptography.sha256(byteArrayOf()).hex)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JdkCryptography.sha256("abc".toByteArray()).hex)
    }

    @Test
    fun `Ed25519 verifies RFC8032 empty message vector using 32 raw public bytes`() {
        val key = PublicKeyHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = SignatureHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(JdkCryptography.verify(key, byteArrayOf(), signature))
        assertFalse(JdkCryptography.verify(key, byteArrayOf(1), signature))
        assertFalse(JdkCryptography.verify(PublicKeyHex("f".repeat(64)), byteArrayOf(), signature))
    }

    @Test
    fun `wallets have independent raw keys and sign original bytes privately`() {
        val signer = JdkWalletSigner()
        val a = signer.createWallet("A")
        val b = signer.createWallet("B")
        val c = signer.createWallet("C")
        assertEquals(3, setOf(a.publicKey, b.publicKey, c.publicKey).size)
        for (wallet in listOf(a, b, c)) {
            assertEquals(32, wallet.publicKey.hex.hexBytes().size)
            assertEquals(Address(JdkCryptography.sha256(wallet.publicKey.hex.hexBytes()).hex), wallet.address)
        }
        val message = "Blockchainlab 2".toByteArray()
        val signature = signer.sign(a.address, message)
        assertTrue(JdkCryptography.verify(a.publicKey, message, signature))
        assertFalse(JdkCryptography.verify(a.publicKey, "Blockchainlab 3".toByteArray(), signature))
        assertFalse(JdkCryptography.verify(b.publicKey, message, signature))
        assertFailsWith<IllegalArgumentException> { signer.sign(Address("0".repeat(64)), message) }
        assertFalse(a.toString().contains("private", ignoreCase = true))
    }
}
