package consensus

import ledger.*
import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ConsensusTest {
    private val blockchain = Blockchain().apply {
        listOf("10", "20", "5").forEachIndexed { i, amount -> createTransfer(wallets[i].address, wallets[(i + 1) % 3].address, amount) }
    }
    private val engine = DefaultConsensusEngine()

    @Test
    fun `B1 B2 are valid B3 fails on signature and minimum valid hash wins independently of order`() {
        val candidates = blockchain.conflictCandidates()
        val validator = DefaultBlockValidator()
        candidates.take(2).forEach { assertIs<BlockValidation.Valid>(validator.validate(it, blockchain.snapshot())) }
        assertEquals("SIGNATURE", assertIs<BlockValidation.Invalid>(validator.validate(candidates[2], blockchain.snapshot())).error.code)
        val before = blockchain.snapshot()
        val result = engine.run(candidates, before)
        val expected = candidates.take(2).minBy { it.blockHash.hex }.blockHash
        assertEquals(expected, result.winner?.blockHash)
        assertEquals(3, result.votes.size)
        assertEquals(NodeId.entries.toSet(), result.votes.map { it.nodeId }.toSet())
        assertTrue(result.votes.all { it.blockHash == expected })
        assertEquals(9, result.evaluations.size)
        assertEquals(expected, engine.run(candidates.reversed(), before).winner?.blockHash)
        assertEquals(before.chain, blockchain.snapshot().chain)
        assertEquals(before.ledger.accounts, blockchain.snapshot().ledger.accounts)
        assertEquals(before.pending, blockchain.snapshot().pending)
    }

    @Test
    fun `repeated proposals never add votes and malformed duplicate hash cannot suppress valid proposal`() {
        val candidates = blockchain.conflictCandidates()
        val expected = candidates.take(2).minBy { it.blockHash.hex }.blockHash
        assertEquals(3, engine.run(candidates + candidates + candidates, blockchain.snapshot()).votes.size)
        val badCopy = candidates[0].copy(transactions = emptyList())
        for (order in listOf(listOf(badCopy) + candidates, candidates + badCopy)) {
            assertEquals(expected, engine.run(order, blockchain.snapshot()).winner?.blockHash)
        }
        val invalidOnly = engine.run(listOf(candidates[2]), blockchain.snapshot())
        assertNull(invalidOnly.winner)
        assertTrue(invalidOnly.votes.all { it.blockHash == null })
    }

    @Test
    fun `two abstentions leave all state unchanged and quorum commits exactly one winner`() {
        val candidates = blockchain.conflictCandidates()
        val before = blockchain.snapshot()
        val noQuorum = blockchain.resolve(candidates, setOf(NodeId.N1, NodeId.N2))
        assertEquals("NO_QUORUM", noQuorum.status)
        assertEquals(2, noQuorum.votes.count { it.blockHash == null })
        assertEquals(before.chain, blockchain.snapshot().chain)
        assertEquals(before.ledger.accounts, blockchain.snapshot().ledger.accounts)
        assertEquals(before.pending, blockchain.snapshot().pending)
        val result = blockchain.resolve(candidates)
        assertEquals("ACCEPTED", result.status)
        assertEquals(2, blockchain.snapshot().chain.size)
        assertEquals(result.winner?.blockHash, blockchain.snapshot().tip.blockHash)
        assertTrue(blockchain.snapshot().pending.isEmpty())
        assertEquals(listOf(95L, 90L, 115L), blockchain.wallets.map { blockchain.snapshot().ledger.accounts.getValue(it.address).balance })
        assertEquals("NO_QUORUM", blockchain.resolve(candidates).status)
        assertEquals(2, blockchain.snapshot().chain.size)
    }
}
