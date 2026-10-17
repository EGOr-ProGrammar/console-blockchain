package model

import org.junit.jupiter.api.Test
import kotlin.test.*

class ValuesTest {
    @Test
    fun `hex values reject uppercase prefixes whitespace nonhex and incorrect lengths`() {
        for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "0x" + "a".repeat(64), " " + "a".repeat(64))) {
            assertFailsWith<IllegalArgumentException>(invalid) { Address(invalid) }
            assertFailsWith<IllegalArgumentException>(invalid) { Hash256(invalid) }
            assertFailsWith<IllegalArgumentException>(invalid) { PublicKeyHex(invalid) }
        }
        for (invalid in listOf("", "a".repeat(127), "a".repeat(129), "A".repeat(128))) {
            assertFailsWith<IllegalArgumentException> { SignatureHex(invalid) }
        }
        Address("a".repeat(64))
        Hash256("0".repeat(64))
        PublicKeyHex("f".repeat(64))
        SignatureHex("b".repeat(128))
    }
}
