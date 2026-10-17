package crypto

import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TransactionEncodingTest {
    @Test
    fun `transaction encoding is flat and signing body excludes id and signature`() {
        val body =
            TransactionBody(Address("a".repeat(64)), Address("b".repeat(64)), 10, 0, PublicKeyHex("c".repeat(64)))
        val tx = SignedTransaction(body, Hash256("d".repeat(64)), SignatureHex("e".repeat(128)))
        assertEquals(setOf("sender", "recipient", "amount", "nonce", "public_key"), body.canonicalObject().keys)
        assertEquals(body.canonicalObject().keys + setOf("tx_id", "signature"), tx.canonicalObject().keys)
        val expected =
            """{"amount":10,"nonce":0,"public_key":"${body.publicKey.hex}","recipient":"${body.recipient.hex}","sender":"${body.sender.hex}"}"""
        assertEquals(expected, CanonicalJson.encode(body.canonicalObject()).decodeToString())
        assertFalse(CanonicalJson.encode(body.canonicalObject()).decodeToString().contains("signature"))
        assertEquals(body.canonicalObject(), tx.copy(signature = SignatureHex("f".repeat(128))).body.canonicalObject())
    }

    @Test
    fun `raw Ed25519 key round trip preserves all bytes`() {
        val raw = PublicKeyHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        assertEquals(raw, raw.toJdkKey().rawHex())
    }
}
