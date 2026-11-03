package ledger

import model.LedgerSnapshot
import model.SignedTransaction
import model.ValidationResult

/** Checks public fields, signature, nonce and confirmed balance without changing the snapshot. */
/** Проверяет подпись, адрес, nonce и достаточность средств относительно переданного снимка. */
interface TransactionValidator {
    fun validate(transaction: SignedTransaction, snapshot: LedgerSnapshot): ValidationResult
}
