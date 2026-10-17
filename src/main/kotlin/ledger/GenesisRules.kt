package ledger

import crypto.*
import model.*

object GenesisRules {
    fun create(wallets: List<WalletInfo>): Genesis {
        require(wallets.size == 3 && wallets.map { it.address }.distinct().size == 3)
        val genesis =
            Genesis(
                Block(BlockHeader(0, Hash256.ZERO, "GENESIS", Hash256.ZERO), emptyList(), Hash256.ZERO),
                wallets.map { Allocation(it.address, 100) }.sortedBy { it.address.hex },
            )

        return Genesis(genesis.block.copy(blockHash = hash(genesis)), genesis.allocations)
    }

    fun validate(genesis: Genesis): ValidationResult {
        fun invalid(message: String) = ValidationResult.Invalid(ValidationError("GENESIS", message, blockIndex = 0))

        if (genesis.block.header !=
            BlockHeader(
                0,
                Hash256.ZERO,
                "GENESIS",
                Hash256.ZERO,
            ) || genesis.block.transactions.isNotEmpty()
        ) {
            return invalid("Genesis must have zero fields, producer GENESIS and no transactions")
        }

        val allocations = genesis.allocations

        if (allocations.size != 3 || allocations.map { it.address }.distinct().size != 3 ||
            allocations.any { it.amount != 100L } || allocations != allocations.sortedBy { it.address.hex }
        ) {
            return invalid("Expected three unique sorted allocations of 100")
        }

        if (genesis.block.blockHash != hash(genesis)) return invalid("Genesis header hash differs")

        return ValidationResult.Valid
    }

    fun initialLedger(genesis: Genesis): LedgerSnapshot {
        require(validate(genesis) == ValidationResult.Valid) { "Invalid genesis" }

        return LedgerSnapshot(genesis.allocations.associate { it.address to AccountState(it.amount, 0) })
    }

    private fun hash(genesis: Genesis) = JdkCryptography.sha256(CanonicalJson.encode(genesis.canonicalHeader()))
}
