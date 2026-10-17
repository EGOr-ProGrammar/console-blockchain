package com.egorroman.blockchain.ledger

import com.egorroman.blockchain.model.*

/** Checks public fields, signature, nonce and confirmed balance without changing the snapshot. */
interface TransactionValidator {
    fun validate(transaction: SignedTransaction, snapshot: LedgerSnapshot): ValidationResult
}

/** Valid contains a new state after sequential application; an invalid block applies nothing. */
interface BlockValidator {
    fun validate(block: Block, snapshot: BlockchainSnapshot): BlockValidation
}
