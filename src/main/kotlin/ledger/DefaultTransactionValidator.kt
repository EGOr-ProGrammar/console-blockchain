package ledger

import crypto.*
import model.*

class DefaultTransactionValidator : TransactionValidator {
    override fun validate(transaction: SignedTransaction, snapshot: LedgerSnapshot): ValidationResult {
        fun invalid(code: String, message: String) = ValidationResult.Invalid(ValidationError(code, message))
        val body = transaction.body
        val sender = snapshot.accounts[body.sender]
        val recipient = snapshot.accounts[body.recipient]
        if (sender == null || recipient == null || body.sender == body.recipient) {
            return invalid("ADDRESS", "Sender and recipient must be known and different")
        }
        if (Address(JdkCryptography.sha256(body.publicKey.hex.hexBytes()).hex) != body.sender) {
            return invalid("SENDER_KEY", "Public key does not derive the sender address")
        }
        val message = CanonicalJson.encode(body.canonicalObject())
        if (JdkCryptography.sha256(message) != transaction.txId) return invalid("TX_ID", "Transaction body hash differs")
        if (!JdkCryptography.verify(body.publicKey, message, transaction.signature)) return invalid("SIGNATURE", "Ed25519 signature is invalid")
        if (body.nonce != sender.nextNonce) return invalid("NONCE", "Expected nonce ${sender.nextNonce}, received ${body.nonce}")
        if (body.amount > sender.balance) return invalid("BALANCE", "Amount exceeds confirmed balance ${sender.balance}")
        try {
            Math.addExact(recipient.balance, body.amount)
            Math.addExact(sender.nextNonce, 1)
        } catch (_: ArithmeticException) {
            return invalid("OVERFLOW", "Balance or nonce would overflow Long")
        }
        return ValidationResult.Valid
    }
}
