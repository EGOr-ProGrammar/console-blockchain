package ledger

import crypto.*
import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class BlocksTest {
    private val blockchain = Blockchain()
    private val validator = DefaultBlockValidator()
    private val wallets = blockchain.wallets

    private fun seed() {
        listOf(10L, 20L, 5L).forEachIndexed { i, amount ->
            assertEquals(
                ValidationResult.Valid,
                blockchain.createTransfer(wallets[i].address, wallets[(i + 1) % 3].address, amount.toString()).result
            )
        }
    }

    private fun rehash(block: Block, transactions: List<SignedTransaction>): Block {
        val header = block.header.copy(merkleRoot = Merkle.build(transactions).root)
        return block.copy(header, transactions, JdkCryptography.sha256(CanonicalJson.encode(header.canonicalObject())))
    }

    private fun invalid(block: Block, code: String) {
        assertEquals(
            code,
            assertIs<BlockValidation.Invalid>(validator.validate(block, blockchain.snapshot())).error.code
        )
    }

    @Test
    fun `block validation calculates new state without mutation then commit is atomic and candidate becomes stale`() {
        seed()
        val before = blockchain.snapshot()
        val candidate = blockchain.buildCandidate("P1")
        assertEquals(1, candidate.header.index)
        assertEquals(before.tip.blockHash, candidate.header.prevHash)
        val valid = assertIs<BlockValidation.Valid>(validator.validate(candidate, before))
        assertEquals(listOf(95L, 90L, 115L), wallets.map { valid.state.accounts.getValue(it.address).balance })
        assertEquals(listOf(1L, 1L, 1L), wallets.map { valid.state.accounts.getValue(it.address).nextNonce })
        assertEquals(before.ledger.accounts, blockchain.snapshot().ledger.accounts)
        assertEquals(1, blockchain.snapshot().chain.size)
        assertIs<BlockValidation.Valid>(blockchain.commit(candidate))
        assertEquals(valid.state.accounts, blockchain.snapshot().ledger.accounts)
        assertTrue(blockchain.snapshot().pending.isEmpty())
        assertEquals(2, blockchain.snapshot().chain.size)
        assertIs<BlockValidation.Invalid>(blockchain.commit(candidate))
        assertEquals(2, blockchain.snapshot().chain.size)
        assertEquals(300L, blockchain.snapshot().ledger.accounts.values.sumOf { it.balance })
        assertEquals(
            "NONCE",
            assertIs<ValidationResult.Invalid>(blockchain.submit(candidate.transactions.first()).result).error.code
        )
    }

    @Test
    fun `rejects size duplicate id height previous hash merkle and header corruption`() {
        seed()
        val candidate = blockchain.buildCandidate("P1")
        invalid(candidate.copy(transactions = emptyList()), "SIZE")
        invalid(candidate.copy(transactions = candidate.transactions + candidate.transactions.first()), "SIZE")
        invalid(
            candidate.copy(transactions = listOf(candidate.transactions.first(), candidate.transactions.first())),
            "DUPLICATE_TX"
        )
        invalid(candidate.copy(header = candidate.header.copy(index = 2)), "INDEX")
        invalid(candidate.copy(header = candidate.header.copy(prevHash = Hash256.ZERO)), "PREV_HASH")
        invalid(candidate.copy(header = candidate.header.copy(merkleRoot = Hash256.ZERO)), "MERKLE_ROOT")
        invalid(candidate.copy(blockHash = Hash256.ZERO), "BLOCK_HASH")
    }

    @Test
    fun `invalid final transaction after rehashing applies no partial state`() {
        seed()
        val before = blockchain.snapshot()
        val candidate = blockchain.buildCandidate("P1")
        val bad = candidate.transactions.last().copy(signature = SignatureHex("0".repeat(128)))
        val corrupted = rehash(candidate, candidate.transactions.dropLast(1) + bad)
        val result = assertIs<BlockValidation.Invalid>(blockchain.commit(corrupted))
        assertEquals("SIGNATURE", result.error.code)
        assertEquals(2, result.error.transactionIndex)
        assertEquals(before.ledger.accounts, blockchain.snapshot().ledger.accounts)
        assertEquals(before.pending, blockchain.snapshot().pending)
        assertEquals(before.chain, blockchain.snapshot().chain)
    }

    @Test
    fun `sequential validation permits spending funds received earlier in same block`() {
        val a = wallets[0];
        val b = wallets[1];
        val c = wallets[2]
        fun signed(sender: WalletInfo, recipient: WalletInfo, amount: Long): SignedTransaction {
            val body = TransactionBody(sender.address, recipient.address, amount, 0, sender.publicKey)
            val bytes = CanonicalJson.encode(body.canonicalObject())
            return SignedTransaction(body, JdkCryptography.sha256(bytes), blockchain.signer.sign(sender.address, bytes))
        }

        val transactions = listOf(signed(a, b, 100), signed(b, c, 200))
        val block = blockchain.buildCandidate("P1", transactions)
        val state = assertIs<BlockValidation.Valid>(validator.validate(block, blockchain.snapshot())).state
        assertEquals(0L, state.accounts.getValue(b.address).balance)
        invalid(blockchain.buildCandidate("P2", transactions.reversed()), "BALANCE")
    }

    @Test
    fun `commit removes only included pending records`() {
        seed()
        val before = blockchain.snapshot()
        assertIs<BlockValidation.Valid>(blockchain.commit(blockchain.buildCandidate("P1", before.pending.take(1))))
        assertEquals(before.pending.drop(1), blockchain.snapshot().pending)
        assertEquals(90L, blockchain.snapshot().ledger.accounts.getValue(wallets[0].address).balance)
    }

    @Test
    fun `accepted alternative transaction evicts stale nonce but preserves other pending senders`() {
        seed()
        val before = blockchain.snapshot()
        val original = before.pending.first()
        val body = original.body.copy(amount = 11)
        val bytes = CanonicalJson.encode(body.canonicalObject())
        val alternative =
            SignedTransaction(body, JdkCryptography.sha256(bytes), blockchain.signer.sign(body.sender, bytes))

        assertNotNull(blockchain.resolve(listOf(blockchain.buildCandidate("P1", listOf(alternative)))).winner)

        assertEquals(before.pending.drop(1), blockchain.snapshot().pending)
        assertEquals(
            ValidationResult.Valid,
            blockchain.createTransfer(wallets[0].address, wallets[1].address, "1").result
        )
        assertEquals(1L, blockchain.snapshot().pending.last().body.nonce)
    }
}
