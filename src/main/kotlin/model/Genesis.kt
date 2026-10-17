package model

import kotlinx.serialization.Serializable

@Serializable
data class Allocation(val address: Address, val amount: Long) {
    init {
        require(amount > 0)
    }
}

class Genesis(val block: Block, allocations: List<Allocation>) {
    val allocations: List<Allocation> = frozenList(allocations)
}
