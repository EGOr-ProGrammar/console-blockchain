package ledger

import model.*

/** Valid contains a new state after sequential application; an invalid block applies nothing. */
interface BlockValidator {
    fun validate(block: Block, snapshot: BlockchainSnapshot): BlockValidation
}
