package ledger

import crypto.*
import model.*
import consensus.DefaultConsensusEngine
import java.util.Collections

class SubmissionResult(val transaction: SignedTransaction?, checks: Map<NodeId, ValidationResult>, val result: ValidationResult) {
    val checks: Map<NodeId, ValidationResult> = Collections.unmodifiableMap(LinkedHashMap(checks))
}

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
            pending.removeAll { transactionValidator.validate(it, ledger) != ValidationResult.Valid }
        }
        return result
    }

    fun resolve(candidates: List<Block>, abstainingNodes: Set<NodeId> = emptySet()): ConsensusResult {
        val result = DefaultConsensusEngine().run(candidates, snapshot(), abstainingNodes)
        result.winner?.let { check(commit(it) is BlockValidation.Valid) { "Winner must still extend current state" } }
        return result
    }

    fun conflictCandidates(): List<Block> {
        require(pending.size == 3)
        val first = buildCandidate("P1")
        val second = buildCandidate("P2", pending.reversed())
        val original = pending.first()
        val body = original.body.copy(amount = original.body.amount + 1)
        val changed = original.copy(body = body, txId = JdkCryptography.sha256(CanonicalJson.encode(body.canonicalObject())))
        val transactions = listOf(changed) + pending.drop(1)
        val header = first.header.copy(merkleRoot = Merkle.build(transactions).root)
        val third = Block(header, transactions, JdkCryptography.sha256(CanonicalJson.encode(header.canonicalObject())))
        return listOf(first, second, third)
    }
}
