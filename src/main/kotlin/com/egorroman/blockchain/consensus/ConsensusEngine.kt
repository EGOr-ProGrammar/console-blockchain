package com.egorroman.blockchain.consensus

import com.egorroman.blockchain.model.*

/** Three distinct voters independently prefer the least valid hash; two matching votes form quorum. */
interface ConsensusEngine {
    fun run(
        candidates: List<Block>,
        snapshot: BlockchainSnapshot,
        abstainingNodes: Set<NodeId> = emptySet(),
    ): ConsensusResult
}
