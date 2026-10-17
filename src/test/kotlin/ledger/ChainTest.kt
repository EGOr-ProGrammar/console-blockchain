package ledger

import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChainTest {
    @Test
    fun `chain audit detects genesis and accepted block damage with location`() {
        val blockchain = Blockchain()
        blockchain.createTransfer(blockchain.wallets[0].address, blockchain.wallets[1].address, "10")
        blockchain.commit(blockchain.buildCandidate("P1"))
        val snapshot = blockchain.snapshot()
        assertEquals(ValidationResult.Valid, ChainValidator.validate(snapshot.genesis, snapshot.chain))
        val badGenesis = Genesis(snapshot.genesis.block, snapshot.genesis.allocations.map { it.copy(amount = 101) })
        assertEquals(
            0L,
            assertIs<ValidationResult.Invalid>(ChainValidator.validate(badGenesis, snapshot.chain)).error.blockIndex
        )
        val changed = snapshot.chain[1].copy(blockHash = Hash256.ZERO)
        assertEquals(
            1L,
            assertIs<ValidationResult.Invalid>(
                ChainValidator.validate(
                    snapshot.genesis,
                    listOf(snapshot.genesis.block, changed)
                )
            ).error.blockIndex
        )
        val transaction = snapshot.chain[1].transactions.first()
        val changedTx = transaction.copy(body = transaction.body.copy(amount = 11))
        val damaged = snapshot.chain[1].copy(transactions = listOf(changedTx))
        assertIs<ValidationResult.Invalid>(
            ChainValidator.validate(
                snapshot.genesis,
                listOf(snapshot.genesis.block, damaged)
            )
        )
        assertIs<ValidationResult.Invalid>(ChainValidator.validate(snapshot.genesis, emptyList()))
        assertIs<ValidationResult.Invalid>(ChainValidator.validate(snapshot.genesis, snapshot.chain.drop(1)))
        assertEquals(ValidationResult.Valid, ChainValidator.validate(snapshot.genesis, snapshot.chain))
    }
}
