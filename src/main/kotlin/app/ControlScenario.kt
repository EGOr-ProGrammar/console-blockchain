package app

import model.*
import ledger.*
import consensus.DefaultConsensusEngine
import report.*
import kotlinx.serialization.json.*

object ControlScenario {
    fun run(session: LabSession): List<ExperimentResult> {
        val results = mutableListOf<ExperimentResult>()
        fun experiment(name: String, expected: String, actual: String, passed: Boolean) {
            val result = ExperimentResult(name, expected, actual, passed)
            results += result
            session.record("experiment", buildJsonObject {
                put("name", name); put("expected", expected); put("actual", actual); put("passed", passed)
            })
        }
        fun code(result: ValidationResult): String = when (result) {
            ValidationResult.Valid -> "VALID"
            is ValidationResult.Invalid -> result.error.code
        }
        val blockchain = session.blockchain
        val wallets = blockchain.wallets
        val signature = session.signMessage()
        val signaturePassed = signature.getValue("original_valid").jsonPrimitive.boolean &&
            !signature.getValue("changed_valid").jsonPrimitive.boolean && !signature.getValue("other_key_valid").jsonPrimitive.boolean
        experiment("message_signature", "true/false/false", "${signature["original_valid"]}/${signature["changed_valid"]}/${signature["other_key_valid"]}", signaturePassed)

        val invalidAmounts = listOf("0", "-1", "0.5", "true", "false", "9223372036854775808").map {
            code(session.transfer(wallets[0].address, wallets[1].address, it).result)
        }
        experiment("invalid_amount", "AMOUNT for every invalid input", invalidAmounts.joinToString(), invalidAmounts.all { it == "AMOUNT" })
        val overspend = code(session.transfer(wallets[0].address, wallets[1].address, "101").result)
        experiment("overspend", "BALANCE", overspend, overspend == "BALANCE" && blockchain.snapshot().pending.isEmpty())

        val submissions = listOf("10", "20", "5").mapIndexed { index, amount ->
            session.transfer(wallets[index].address, wallets[(index + 1) % 3].address, amount)
        }
        val duplicate = code(session.transfer(wallets[0].address, wallets[1].address, "1").result)
        experiment("pending_sender", "PENDING_SENDER; balances unchanged", duplicate,
            duplicate == "PENDING_SENDER" && blockchain.snapshot().pending.size == 3 && blockchain.snapshot().ledger.accounts.values.all { it.balance == 100L && it.nextNonce == 0L })

        val trees = (1..3).map { session.merkle(it) }
        val treesPassed = trees.all { it != null } && trees[0]?.levels?.size == 1 && trees[1]?.levels?.size == 2 &&
            trees[2]?.levels?.size == 3 && trees[2]?.levels?.first()?.duplicatedLast == true && blockchain.snapshot().pending.size == 3
        experiment("merkle_1_2_3", "1/2/3 levels; last leaf duplicated for three", trees.map { it?.levels?.size }.joinToString(), treesPassed)
        val reversed = Merkle.build(blockchain.snapshot().pending.reversed())
        session.record("transaction_order", buildJsonObject { put("forward", trees[2]?.publicJson() ?: JsonNull); put("reversed", reversed.publicJson()) })
        experiment("transaction_order", "different roots", "${trees[2]?.root?.hex} / ${reversed.root.hex}", trees[2]?.root != reversed.root)

        val candidates = session.prepareCandidates()
        val evaluations = candidates.map { DefaultBlockValidator().validate(it, blockchain.snapshot()) }
        val tampered = (evaluations.getOrNull(2) as? BlockValidation.Invalid)?.error?.code ?: "NOT_REJECTED"
        experiment("tampered_amount", "SIGNATURE after rehashing", tampered, tampered == "SIGNATURE")

        val engine = DefaultConsensusEngine()
        val ordered = engine.run(candidates, blockchain.snapshot())
        val permuted = engine.run(candidates.reversed(), blockchain.snapshot())
        session.record("proposal_order", buildJsonObject { put("original", ordered.publicJson()); put("permuted", permuted.publicJson()) })
        experiment("proposal_order", "identical node decisions", permuted.votes.joinToString { "${it.nodeId}:${it.blockHash?.hex}" }, ordered.votes == permuted.votes && ordered.winner != null)

        val beforeNoQuorum = session.state()
        val noQuorum = session.resolve(setOf(NodeId.N1, NodeId.N2))
        experiment("no_quorum", "NO_QUORUM; all state unchanged", noQuorum.status, noQuorum.winner == null && session.state() == beforeNoQuorum)
        val consensus = session.resolve()
        val winner = candidates.take(2).minByOrNull { it.blockHash.hex }
        experiment("candidate_conflict", "one valid minimum hash winner; B3 excluded", consensus.status,
            consensus.winner?.blockHash == winner?.blockHash && winner != null && evaluations.take(2).all { it is BlockValidation.Valid } && tampered == "SIGNATURE" && blockchain.snapshot().chain.size == 2)

        val replay = submissions.first().transaction?.let { code(session.submit(it).result) } ?: "MISSING_TRANSACTION"
        experiment("replay", "NONCE", replay, replay == "NONCE")
        val snapshot = blockchain.snapshot()
        val damaged = snapshot.tip.copy(blockHash = Hash256.ZERO)
        val damagedResult = session.verifyChain(snapshot.genesis, snapshot.chain.dropLast(1) + damaged)
        val damagedGenesis = Genesis(snapshot.genesis.block, snapshot.genesis.allocations.mapIndexed { i, allocation -> if (i == 0) allocation.copy(amount = 101) else allocation })
        val genesisResult = session.verifyChain(damagedGenesis, snapshot.chain)
        val validChain = session.verifyChain()
        experiment("damaged_chain", "damaged copies rejected; original valid", "${code(damagedResult)} / ${code(genesisResult)} / ${code(validChain)}",
            damagedResult is ValidationResult.Invalid && genesisResult is ValidationResult.Invalid && validChain == ValidationResult.Valid)

        val balances = wallets.map { snapshot.ledger.accounts.getValue(it.address).balance }
        val control = balances == listOf(95L, 90L, 115L) && snapshot.ledger.accounts.values.all { it.nextNonce == 1L } &&
            snapshot.pending.isEmpty() && snapshot.ledger.accounts.values.sumOf { it.balance } == 300L
        experiment("control_balances", "95/90/115; nonce 1/1/1; pending 0; total 300", balances.joinToString("/"), control)
        return results.toList()
    }
}
