package ledger

import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class MerkleTest {
    // Independent vectors prepared with Python json.dumps(sort_keys=True, separators=(',', ':')) and hashlib.
    private val transactions = (1L..3).map {
        SignedTransaction(TransactionBody(Address("a".repeat(64)), Address("b".repeat(64)), it, 0, PublicKeyHex("c".repeat(64))), Hash256("d".repeat(64)), SignatureHex("e".repeat(128)))
    }
    private val leaves = listOf("5b8d3cc3f1e0699a6addc260ed4b4f58181c9812c6a4eca342cdb073d3840349", "ef02f3c120e883a4810ab7aaab789083d0a33dff36c77892ee4ce1f234331a72", "be2a73c82b97ec8b696c4ce10f30096ff3a47d32c99233cdc730a0bec4960a44").map(::Hash256)

    @Test
    fun `one two three signed records match independent byte concatenation vectors`() {
        assertEquals(leaves[0], Merkle.build(transactions.take(1)).root)
        assertEquals(Hash256("d93acb9f42f0ba452d325eb6e71bfb8a8bd4a9b83c6aada01656ead6cf9e049d"), Merkle.build(transactions.take(2)).root)
        val tree = Merkle.build(transactions)
        assertEquals(Hash256("e6b96a58fb6ce0c1aec2e03896e9e88204b6b20957ccff226048d522c921a392"), tree.root)
        assertEquals(leaves + leaves.last(), tree.levels[0].hashes)
        assertTrue(tree.levels[0].duplicatedLast)
        assertEquals(listOf(Hash256("d93acb9f42f0ba452d325eb6e71bfb8a8bd4a9b83c6aada01656ead6cf9e049d"), Hash256("a496898ab8acc8fab4d65f483af246dd964a5b944cf925753ca64d80fdeafbb3")), tree.levels[1].hashes)
        assertFalse(tree.levels[1].duplicatedLast)
        assertEquals(3, transactions.size)
        assertNotEquals(tree.root, Merkle.build(transactions.reversed()).root)
    }

    @Test
    fun `empty or oversized ordinary trees are rejected and returned levels cannot be mutated`() {
        assertFailsWith<IllegalArgumentException> { Merkle.build(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Merkle.build(transactions + transactions.first()) }
        val tree = Merkle.build(transactions)
        assertFailsWith<UnsupportedOperationException> { (tree.levels as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (tree.levels[0].hashes as MutableList).clear() }
    }
}
