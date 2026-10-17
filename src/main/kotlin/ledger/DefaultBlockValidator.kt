package ledger

import crypto.*
import model.*

class DefaultBlockValidator : BlockValidator {
    override fun validate(block: Block, snapshot: BlockchainSnapshot): BlockValidation {
        fun invalid(code: String, message: String) = BlockValidation.Invalid(ValidationError(code, message, blockIndex = block.header.index))
        if (block.transactions.size !in 1..3) return invalid("SIZE", "A block has 1–3 transactions")
        if (block.transactions.map { it.txId }.distinct().size != block.transactions.size) return invalid("DUPLICATE_TX", "Duplicate transaction id in block")
        if (snapshot.tip.header.index == Long.MAX_VALUE || block.header.index != snapshot.tip.header.index + 1) return invalid("INDEX", "Block is not at the next height")
        if (block.header.prevHash != snapshot.tip.blockHash) return invalid("PREV_HASH", "Block does not extend current tip")
        if (block.header.merkleRoot != Merkle.build(block.transactions).root) return invalid("MERKLE_ROOT", "Merkle root differs from signed records")
        if (block.blockHash != JdkCryptography.sha256(CanonicalJson.encode(block.header.canonicalObject()))) return invalid("BLOCK_HASH", "Block header hash differs")
        val accounts = snapshot.ledger.accounts.toMutableMap()
        val validator = DefaultTransactionValidator()
        block.transactions.forEachIndexed { index, transaction ->
            val result = validator.validate(transaction, LedgerSnapshot(accounts))
            if (result is ValidationResult.Invalid) return BlockValidation.Invalid(result.error.copy(transactionIndex = index, blockIndex = block.header.index))
            val body = transaction.body
            val sender = accounts.getValue(body.sender)
            val recipient = accounts.getValue(body.recipient)
            accounts[body.sender] = AccountState(sender.balance - body.amount, Math.addExact(sender.nextNonce, 1))
            accounts[body.recipient] = recipient.copy(balance = Math.addExact(recipient.balance, body.amount))
        }
        return BlockValidation.Valid(LedgerSnapshot(accounts))
    }
}
