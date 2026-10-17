package ledger

import model.*

object ChainValidator {
    fun validate(genesis: Genesis, chain: List<Block>): ValidationResult {
        val genesisResult = GenesisRules.validate(genesis)
        if (genesisResult is ValidationResult.Invalid) return genesisResult
        val first = chain.firstOrNull()
        if (first == null || first.header != genesis.block.header || first.blockHash != genesis.block.blockHash || first.transactions.isNotEmpty()) {
            return ValidationResult.Invalid(ValidationError("GENESIS", "Chain must begin with the declared genesis", blockIndex = 0))
        }
        var state = GenesisRules.initialLedger(genesis)
        val accepted = mutableListOf(first)
        val validator = DefaultBlockValidator()
        for (block in chain.drop(1)) {
            when (val result = validator.validate(block, BlockchainSnapshot(genesis, accepted, state, emptyList()))) {
                is BlockValidation.Invalid -> return ValidationResult.Invalid(result.error)
                is BlockValidation.Valid -> state = result.state
            }
            accepted += block
        }
        return ValidationResult.Valid
    }
}
