package ledger

import model.*

/** Valid contains a new state after sequential application; an invalid block applies nothing. */
/** Проверяет блок на снимке состояния, не изменяя подтверждённый реестр. */
interface BlockValidator {
    fun validate(block: Block, snapshot: BlockchainSnapshot): BlockValidation
}
