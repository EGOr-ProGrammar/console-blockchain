package model

import kotlinx.serialization.Serializable

@Serializable
enum class NodeId { N0, N1, N2 }

@Serializable
data class Vote(val nodeId: NodeId, val blockHash: Hash256?)

data class CandidateEvaluation(val nodeId: NodeId, val block: Block, val result: BlockValidation)

class ConsensusResult(evaluations: List<CandidateEvaluation>, votes: List<Vote>, val winner: Block?) {
    val evaluations: List<CandidateEvaluation> = frozenList(evaluations)
    val votes: List<Vote> = frozenList(votes)
    val status: String get() = if (winner == null) "NO_QUORUM" else "ACCEPTED"
}
