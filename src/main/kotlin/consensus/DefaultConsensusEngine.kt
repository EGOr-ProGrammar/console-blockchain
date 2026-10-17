package consensus

import model.*
import ledger.DefaultBlockValidator

class DefaultConsensusEngine : ConsensusEngine {
    override fun run(candidates: List<Block>, snapshot: BlockchainSnapshot, abstainingNodes: Set<NodeId>): ConsensusResult {
        val distinct = candidates.distinctBy { it.header to (it.blockHash to it.transactions) }
        val validator = DefaultBlockValidator()
        val evaluations = mutableListOf<CandidateEvaluation>()
        val votes = NodeId.entries.map { node ->
            val checks = distinct.map { candidate ->
                val copy = candidate.copy(transactions = candidate.transactions.map { it.copy(body = it.body.copy()) })
                CandidateEvaluation(node, copy, validator.validate(copy, snapshot))
            }
            evaluations += checks
            val preferred = checks.filter { it.result is BlockValidation.Valid }
                .distinctBy { it.block.blockHash }.minByOrNull { it.block.blockHash.hex }?.block
            Vote(node, if (node in abstainingNodes) null else preferred?.blockHash)
        }
        val winnerHash = votes.mapNotNull { it.blockHash }.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value >= 2 }?.key
        val winner = evaluations.firstOrNull { it.result is BlockValidation.Valid && it.block.blockHash == winnerHash }?.block
        return ConsensusResult(evaluations, votes, winner)
    }
}
