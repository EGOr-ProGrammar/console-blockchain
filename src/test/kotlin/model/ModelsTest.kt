package model

import org.junit.jupiter.api.Test
import kotlin.test.*

class ModelsTest {
    private val a = Address("a".repeat(64))
    private val b = Address("b".repeat(64))
    private val key = PublicKeyHex("c".repeat(64))
    private val header = BlockHeader(0, Hash256.ZERO, "GENESIS", Hash256.ZERO)

    @Test
    fun `numeric and producer invariants reject impossible persistent values`() {
        for (amount in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TransactionBody(a, b, amount, 0, key) }
            assertFailsWith<IllegalArgumentException> { Allocation(a, amount) }
        }
        assertFailsWith<IllegalArgumentException> { TransactionBody(a, b, 1, -1, key) }
        assertFailsWith<IllegalArgumentException> { AccountState(-1, 0) }
        assertFailsWith<IllegalArgumentException> { AccountState(0, -1) }
        assertFailsWith<IllegalArgumentException> { header.copy(index = -1) }
        assertFailsWith<IllegalArgumentException> { header.copy(producer = "") }
        assertFailsWith<IllegalArgumentException> { header.copy(producer = "П1") }
        TransactionBody(a, b, Long.MAX_VALUE, Long.MAX_VALUE, key)
        AccountState(Long.MAX_VALUE, Long.MAX_VALUE)
    }

    @Test
    fun `snapshots and blocks own immutable copies of caller collections`() {
        val accounts = mutableMapOf(a to AccountState(100, 0))
        val ledger = LedgerSnapshot(accounts)
        val transactions = mutableListOf(SignedTransaction(TransactionBody(a, b, 1, 0, key), Hash256.ZERO, SignatureHex("0".repeat(128))))
        val block = Block(header, transactions, Hash256.ZERO)
        val allocations = mutableListOf(Allocation(a, 100))
        val genesis = Genesis(Block(header, emptyList(), Hash256.ZERO), allocations)
        val chain = mutableListOf(genesis.block)
        val snapshot = BlockchainSnapshot(genesis, chain, ledger, transactions)
        accounts.clear(); transactions.clear(); allocations.clear(); chain.clear()
        assertEquals(100L, ledger.accounts.getValue(a).balance)
        assertEquals(1, block.transactions.size)
        assertEquals(1, genesis.allocations.size)
        assertEquals(1, snapshot.pending.size)
        assertEquals(1, snapshot.chain.size)
        assertFailsWith<UnsupportedOperationException> { (ledger.accounts as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (block.transactions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.chain as MutableList).clear() }
    }

    @Test
    fun `Merkle and consensus models copy caller collections too`() {
        val hashes = mutableListOf(Hash256.ZERO)
        val level = MerkleLevel(hashes, false)
        val levels = mutableListOf(level)
        val tree = MerkleTree(levels, Hash256.ZERO)
        val votes = mutableListOf(Vote(NodeId.N0, null))
        val evaluations = mutableListOf<CandidateEvaluation>()
        val result = ConsensusResult(evaluations, votes, null)
        hashes.clear(); levels.clear(); votes.clear()
        assertEquals(1, tree.levels.size)
        assertEquals(1, level.hashes.size)
        assertEquals(1, result.votes.size)
        assertFailsWith<UnsupportedOperationException> { (result.votes as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (tree.levels as MutableList).clear() }
    }
}
