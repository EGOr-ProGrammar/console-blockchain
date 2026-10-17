package model

import kotlinx.serialization.Serializable

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
