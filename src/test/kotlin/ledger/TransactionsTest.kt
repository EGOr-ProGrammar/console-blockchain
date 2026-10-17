package ledger

import crypto.*
import model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TransactionsTest {
    private val blockchain = Blockchain()
    private val a = blockchain.wallets[0]
    private val b = blockchain.wallets[1]
    private val validator = DefaultTransactionValidator()

    private fun transaction(amount: Long = 10, nonce: Long = 0, recipient: Address = b.address): SignedTransaction {
        val body = TransactionBody(a.address, recipient, amount, nonce, a.publicKey)
        val bytes = CanonicalJson.encode(body.canonicalObject())
        return SignedTransaction(body, JdkCryptography.sha256(bytes), blockchain.signer.sign(a.address, bytes))
    }

    private fun code(result: ValidationResult): String = assertIs<ValidationResult.Invalid>(result).error.code

    @Test
    fun `three node checks add one pending transaction without changing confirmed state`() {
        val before = blockchain.snapshot()
        val result = blockchain.createTransfer(a.address, b.address, "10")
        assertEquals(ValidationResult.Valid, result.result)
        assertEquals(NodeId.entries.toSet(), result.checks.keys)
        assertTrue(result.checks.values.all { it == ValidationResult.Valid })
        assertFailsWith<UnsupportedOperationException> { (result.checks as MutableMap).clear() }
        assertEquals(listOf(result.transaction), blockchain.snapshot().pending)
        assertEquals(before.ledger.accounts, blockchain.snapshot().ledger.accounts)
        assertEquals(0L, blockchain.snapshot().ledger.accounts.getValue(a.address).nextNonce)
        assertTrue(before.pending.isEmpty())
        assertEquals("PENDING_SENDER", code(blockchain.submit(result.transaction!!).result))
        assertEquals(1, blockchain.snapshot().pending.size)
        assertEquals("PENDING_SENDER", code(blockchain.createTransfer(a.address, b.address, "11").result))
    }

    @Test
    fun `input boundary rejects fractional boolean zero negative overflow and malformed amounts`() {
        for (invalid in listOf("0", "-1", "0.5", "1.0", "true", "false", "9223372036854775808", "1e2", "", "+1")) {
            assertEquals("AMOUNT", code(blockchain.createTransfer(a.address, b.address, invalid).result), invalid)
        }
        assertTrue(blockchain.snapshot().pending.isEmpty())
    }

    @Test
    fun `overspend same address and unknown addresses are rejected with complete node results`() {
        for ((tx, expected) in listOf(
            transaction(101) to "BALANCE",
            transaction(recipient = a.address) to "ADDRESS",
            transaction(recipient = Address("0".repeat(64))) to "ADDRESS"
        )) {
            val result = blockchain.submit(tx)
            assertEquals(3, result.checks.size)
            assertTrue(result.checks.values.all { code(it) == expected })
            assertEquals(expected, code(result.result))
        }
        assertTrue(blockchain.snapshot().pending.isEmpty())
    }

    @Test
    fun `validator verifies key ownership txid signature nonce and confirmed balances`() {
        val tx = transaction()
        val snapshot = blockchain.snapshot().ledger
        assertEquals(ValidationResult.Valid, validator.validate(tx, snapshot))
        assertEquals(
            "SENDER_KEY",
            code(validator.validate(tx.copy(body = tx.body.copy(publicKey = b.publicKey)), snapshot))
        )
        assertEquals("TX_ID", code(validator.validate(tx.copy(txId = Hash256.ZERO), snapshot)))
        assertEquals(
            "SIGNATURE",
            code(validator.validate(tx.copy(signature = SignatureHex("0".repeat(128))), snapshot))
        )
        val tampered = tx.copy(body = tx.body.copy(amount = 11))
        val rehashed =
            tampered.copy(txId = JdkCryptography.sha256(CanonicalJson.encode(tampered.body.canonicalObject())))
        assertEquals("SIGNATURE", code(validator.validate(rehashed, snapshot)))
        assertEquals("NONCE", code(validator.validate(transaction(nonce = 1), snapshot)))
        val confirmed = LedgerSnapshot(snapshot.accounts + (a.address to AccountState(90, 1)))
        assertEquals("NONCE", code(validator.validate(tx, confirmed)))
        assertEquals("BALANCE", code(validator.validate(transaction(101), snapshot)))
        assertEquals("ADDRESS", code(validator.validate(tx, LedgerSnapshot(snapshot.accounts - a.address))))
        assertEquals(100L, snapshot.accounts.getValue(a.address).balance)
    }

    @Test
    fun `validator rejects overflow of recipient balance or next nonce`() {
        val snapshot = blockchain.snapshot().ledger
        val richRecipient = LedgerSnapshot(snapshot.accounts + (b.address to AccountState(Long.MAX_VALUE, 0)))
        assertEquals("OVERFLOW", code(validator.validate(transaction(), richRecipient)))
        val exhausted = LedgerSnapshot(snapshot.accounts + (a.address to AccountState(100, Long.MAX_VALUE)))
        assertEquals("OVERFLOW", code(validator.validate(transaction(nonce = Long.MAX_VALUE), exhausted)))
    }
}
