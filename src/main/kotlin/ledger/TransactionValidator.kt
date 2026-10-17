package ledger

import model.*

/** Checks public fields, signature, nonce and confirmed balance without changing the snapshot. */
interface TransactionValidator {
    fun validate(transaction: SignedTransaction, snapshot: LedgerSnapshot): ValidationResult
}
