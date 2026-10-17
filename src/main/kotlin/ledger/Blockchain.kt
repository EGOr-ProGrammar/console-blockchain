package ledger

import crypto.*
import model.*

data class SubmissionResult(val transaction: SignedTransaction?, val checks: Map<NodeId, ValidationResult>, val result: ValidationResult)

class Blockchain(val signer: WalletSigner = JdkWalletSigner()) {
    val wallets: List<WalletInfo> = frozenList(listOf("A", "B", "C").map(signer::createWallet))
    private val genesis = GenesisRules.create(wallets)
    private var ledger = GenesisRules.initialLedger(genesis)
    private val chain = mutableListOf(genesis.block)
    private val pending = mutableListOf<SignedTransaction>()
    private val transactionValidator = DefaultTransactionValidator()

    fun snapshot(): BlockchainSnapshot = BlockchainSnapshot(genesis, chain, ledger, pending)

    fun createTransfer(sender: Address, recipient: Address, amount: String): SubmissionResult {
        val value = amount.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        if (value == null || value <= 0) return failure("AMOUNT", "Expected a positive integer in Long range")
        val wallet = wallets.find { it.address == sender } ?: return failure("ADDRESS", "Unknown sender wallet")
        val body = TransactionBody(sender, recipient, value, ledger.accounts.getValue(sender).nextNonce, wallet.publicKey)
        val bytes = CanonicalJson.encode(body.canonicalObject())
        return submit(SignedTransaction(body, JdkCryptography.sha256(bytes), signer.sign(sender, bytes)))
    }

    fun submit(transaction: SignedTransaction): SubmissionResult {
        val snapshot = LedgerSnapshot(ledger.accounts)
        val checks = NodeId.entries.associateWith {
            val copy = transaction.copy(body = transaction.body.copy())
            val result = transactionValidator.validate(copy, snapshot)
            if (result == ValidationResult.Valid && pending.any { it.body.sender == copy.body.sender }) {
                ValidationResult.Invalid(ValidationError("PENDING_SENDER", "Sender already has a pending transaction"))
            } else result
        }
        val result = checks.values.firstOrNull { it is ValidationResult.Invalid } ?: ValidationResult.Valid
        if (checks.size == 3 && checks.values.all { it == ValidationResult.Valid }) pending += transaction.copy(body = transaction.body.copy())
        return SubmissionResult(transaction, checks, result)
    }

    private fun failure(code: String, message: String) = SubmissionResult(null, emptyMap(), ValidationResult.Invalid(ValidationError(code, message)))

    fun buildCandidate(producer: String, transactions: List<SignedTransaction> = snapshot().pending): Block {
        val header = BlockHeader(Math.addExact(chain.last().header.index, 1), chain.last().blockHash, producer, Merkle.build(transactions).root)
        return Block(header, transactions, JdkCryptography.sha256(CanonicalJson.encode(header.canonicalObject())))
    }

    internal fun commit(block: Block): BlockValidation {
        val result = DefaultBlockValidator().validate(block, snapshot())
        if (result is BlockValidation.Valid) {
            ledger = result.state
            chain += block.copy()
            val included = block.transactions.map { it.txId }.toSet()
            pending.removeAll { it.txId in included }
        }
        return result
    }
}
