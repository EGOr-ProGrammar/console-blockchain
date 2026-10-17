package com.egorroman.blockchain.model

import kotlinx.serialization.Serializable
import java.util.Collections

@Serializable
data class WalletInfo(val name: String, val publicKey: PublicKeyHex, val address: Address)

@Serializable
data class TransactionBody(
    val sender: Address,
    val recipient: Address,
    val amount: Long,
    val nonce: Long,
    val publicKey: PublicKeyHex,
) {
    init { require(amount > 0); require(nonce >= 0) }
}

@Serializable
data class SignedTransaction(val body: TransactionBody, val txId: Hash256, val signature: SignatureHex)

@Serializable
data class AccountState(val balance: Long, val nextNonce: Long) {
    init { require(balance >= 0); require(nextNonce >= 0) }
}

@Serializable
data class Allocation(val address: Address, val amount: Long) {
    init { require(amount > 0) }
}

@Serializable
data class BlockHeader(val index: Long, val prevHash: Hash256, val producer: String, val merkleRoot: Hash256) {
    init {
        require(index >= 0)
        require(producer.isNotEmpty() && producer.all { it.code in 32..126 })
    }
}

class Block(val header: BlockHeader, transactions: List<SignedTransaction>, val blockHash: Hash256) {
    val transactions: List<SignedTransaction> = frozenList(transactions)

    fun copy(
        header: BlockHeader = this.header,
        transactions: List<SignedTransaction> = this.transactions,
        blockHash: Hash256 = this.blockHash,
    ) = Block(header, transactions, blockHash)
}

class Genesis(val block: Block, allocations: List<Allocation>) {
    val allocations: List<Allocation> = frozenList(allocations)
}

class LedgerSnapshot(accounts: Map<Address, AccountState>) {
    val accounts: Map<Address, AccountState> = Collections.unmodifiableMap(LinkedHashMap(accounts))
}

class BlockchainSnapshot(
    val genesis: Genesis,
    chain: List<Block>,
    val ledger: LedgerSnapshot,
    pending: List<SignedTransaction>,
) {
    val chain: List<Block> = frozenList(chain)
    val pending: List<SignedTransaction> = frozenList(pending)
    val tip: Block get() = chain.last()
}

@Serializable
data class MerkleLevel(val hashes: List<Hash256>, val duplicatedLast: Boolean)

@Serializable
data class MerkleTree(val levels: List<MerkleLevel>, val root: Hash256)

@Serializable
data class ValidationError(val code: String, val message: String, val transactionIndex: Int? = null, val blockIndex: Long? = null)

@Serializable
sealed interface ValidationResult {
    @Serializable
    data object Valid : ValidationResult
    @Serializable
    data class Invalid(val error: ValidationError) : ValidationResult
}

sealed interface BlockValidation {
    data class Valid(val state: LedgerSnapshot) : BlockValidation
    data class Invalid(val error: ValidationError) : BlockValidation
}

@Serializable
enum class NodeId { N0, N1, N2 }

@Serializable
data class Vote(val nodeId: NodeId, val blockHash: Hash256?)

data class CandidateEvaluation(val nodeId: NodeId, val block: Block, val result: BlockValidation)

class ConsensusResult(val evaluations: List<CandidateEvaluation>, val votes: List<Vote>, val winner: Block?) {
    val status: String get() = if (winner == null) "NO_QUORUM" else "ACCEPTED"
}

@Serializable
data class ExperimentResult(val name: String, val expected: String, val actual: String, val passed: Boolean)

internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
