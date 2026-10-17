package app

import ledger.*
import model.*
import report.*
import java.nio.file.Path
import crypto.*
import kotlinx.serialization.json.*

class LabSession(root: Path, type: String) {
    val blockchain = Blockchain()
    val reports = FileReportStore(root, type)
    var lastWrite: WriteResult = WriteResult.Saved
        private set
    var candidates: List<Block> = emptyList()
        private set

    init {
        record("session_started", buildJsonObject {
            put("wallets", JsonArray(blockchain.wallets.map { it.publicJson() }))
            put("genesis", blockchain.snapshot().genesis.canonicalHeader())
            put("genesis_hash", blockchain.snapshot().genesis.block.blockHash.hex)
            put("after", state())
        })
    }

    fun state(): JsonObject = blockchain.snapshot().publicJson(blockchain.wallets)

    fun record(operation: String, result: JsonObject) {
        lastWrite = reports.record(operation, result)
    }

    fun signMessage(message: String = "Blockchainlab 2", changed: String = "Blockchainlab 3"): JsonObject {
        val a = blockchain.wallets[0]; val b = blockchain.wallets[1]
        val bytes = message.toByteArray(Charsets.UTF_8)
        val signature = blockchain.signer.sign(a.address, bytes)
        val result = buildJsonObject {
            put("message", message); put("changed_message", changed); put("message_hex", bytes.toHex())
            put("public_key", a.publicKey.hex); put("other_public_key", b.publicKey.hex); put("signature", signature.hex)
            put("original_valid", JdkCryptography.verify(a.publicKey, bytes, signature))
            put("changed_valid", JdkCryptography.verify(a.publicKey, changed.toByteArray(Charsets.UTF_8), signature))
            put("other_key_valid", JdkCryptography.verify(b.publicKey, bytes, signature))
        }
        record("message_signature", result)
        return result
    }

    fun transfer(sender: Address, recipient: Address, amount: String): SubmissionResult {
        val before = state()
        return blockchain.createTransfer(sender, recipient, amount).also { result ->
            recordSubmission("transfer", result, before, buildJsonObject { put("sender", sender.hex); put("recipient", recipient.hex); put("amount_input", amount) })
        }
    }

    fun submit(transaction: SignedTransaction): SubmissionResult {
        val before = state()
        return blockchain.submit(transaction).also { recordSubmission("submit", it, before) }
    }

    private fun recordSubmission(operation: String, submission: SubmissionResult, before: JsonObject, input: JsonObject = buildJsonObject { }) {
        record(operation, buildJsonObject {
            put("input", input); put("before", before); put("after", state()); put("validation", submission.result.publicJson())
            put("checks", buildJsonObject { submission.checks.forEach { (node, result) -> put(node.name, result.publicJson()) } })
            submission.transaction?.let { tx ->
                put("transaction", tx.canonicalObject()); put("canonical_body", tx.body.canonicalObject())
                put("canonical_body_json", CanonicalJson.encode(tx.body.canonicalObject()).decodeToString())
                put("message_hex", CanonicalJson.encode(tx.body.canonicalObject()).toHex())
            }
        })
    }

    fun merkle(count: Int): MerkleTree? {
        val pending = blockchain.snapshot().pending
        if (count !in 1..3 || count > pending.size) {
            record("merkle", buildJsonObject { put("count", count); put("error", "Select 1–3 available pending records") })
            return null
        }
        val tree = Merkle.build(pending.take(count))
        record("merkle", buildJsonObject { put("count", count); put("transactions", JsonArray(pending.take(count).map { it.canonicalObject() })); put("tree", tree.publicJson()) })
        return tree
    }

    fun prepareCandidates(): List<Block> {
        val pending = blockchain.snapshot().pending
        candidates = when (pending.size) {
            0 -> emptyList()
            3 -> blockchain.conflictCandidates()
            else -> listOf(blockchain.buildCandidate("P1"))
        }
        record("candidates", buildJsonObject {
            put("candidates", JsonArray(candidates.mapIndexed { index, block -> buildJsonObject {
                put("name", "B${index + 1}"); put("block", block.publicJson())
                put("validation", DefaultBlockValidator().validate(block, blockchain.snapshot()).publicJson())
            } }))
            if (candidates.isEmpty()) put("error", "Pending is empty")
        })
        return candidates
    }

    fun resolve(abstainingNodes: Set<NodeId> = emptySet()): ConsensusResult {
        val before = state()
        val result = blockchain.resolve(candidates, abstainingNodes)
        record("consensus", JsonObject(result.publicJson() + mapOf("before" to before, "after" to state())))
        return result
    }

    fun verifyChain(genesis: Genesis = blockchain.snapshot().genesis, chain: List<Block> = blockchain.snapshot().chain): ValidationResult {
        val result = ChainValidator.validate(genesis, chain)
        record("chain_check", buildJsonObject {
            put("genesis", genesis.canonicalHeader()); put("chain", JsonArray(chain.map { it.publicJson() }))
            put("validation", result.publicJson()); put("after", state())
        })
        return result
    }

    fun export(root: Path? = null): WriteResult = reports.export(root).also { lastWrite = it }
}
