package consensus

import model.*

/** Three distinct voters independently prefer the least valid hash; two matching votes form quorum. */
/** Синхронное учебное голосование трёх участников; не является сетевым протоколом консенсуса. */
interface ConsensusEngine {
    fun run(
        candidates: List<Block>,
        snapshot: BlockchainSnapshot,
        abstainingNodes: Set<NodeId> = emptySet(),
    ): ConsensusResult
}
