package report

import crypto.*
import model.*
import kotlinx.serialization.json.*

fun ValidationError.publicJson(): JsonObject = buildJsonObject {
    put("code", code); put("message", message)
    transactionIndex?.let { put("transaction_index", it) }
    blockIndex?.let { put("block_index", it) }
}

fun ValidationResult.publicJson(): JsonObject = when (this) {
    ValidationResult.Valid -> buildJsonObject { put("status", "VALID") }
    is ValidationResult.Invalid -> buildJsonObject { put("status", "INVALID"); put("error", error.publicJson()) }
}

fun BlockValidation.publicJson(): JsonObject = when (this) {
    is BlockValidation.Valid -> buildJsonObject { put("status", "VALID"); put("state", state.publicJson()) }
    is BlockValidation.Invalid -> buildJsonObject { put("status", "INVALID"); put("error", error.publicJson()) }
}

fun WalletInfo.publicJson(): JsonObject = buildJsonObject {
    put("name", name); put("public_key", publicKey.hex); put("raw_public_key_hex", publicKey.hex); put("address", address.hex)
}

fun LedgerSnapshot.publicJson(wallets: List<WalletInfo> = emptyList()): JsonArray = JsonArray(accounts.entries.sortedBy { it.key.hex }.map { (address, state) -> buildJsonObject {
    put("name", wallets.find { it.address == address }?.name ?: address.hex)
    put("address", address.hex); put("balance", state.balance); put("next_nonce", state.nextNonce)
} })

fun Block.publicJson(): JsonObject = buildJsonObject {
    put("header", header.canonicalObject()); put("block_hash", blockHash.hex)
    put("transactions", JsonArray(transactions.map { it.canonicalObject() }))
}

fun BlockchainSnapshot.publicJson(wallets: List<WalletInfo>): JsonObject = buildJsonObject {
    put("height", tip.header.index); put("tip_hash", tip.blockHash.hex)
    put("accounts", ledger.publicJson(wallets))
    put("pending", JsonArray(pending.map { it.canonicalObject() }))
    put("chain", JsonArray(chain.map { it.publicJson() }))
}

fun MerkleTree.publicJson(): JsonObject = buildJsonObject {
    put("root", root.hex)
    put("levels", JsonArray(levels.mapIndexed { index, level -> buildJsonObject {
        put("level", index); put("duplicated_last", level.duplicatedLast)
        put("hashes", JsonArray(level.hashes.map { JsonPrimitive(it.hex) }))
    } }))
}

fun ConsensusResult.publicJson(): JsonObject = buildJsonObject {
    put("status", status)
    put("votes", JsonArray(votes.map { vote -> buildJsonObject {
        put("node_id", vote.nodeId.name); put("vote", vote.blockHash?.hex ?: "NO_VOTE")
    } }))
    put("evaluations", JsonArray(evaluations.map { evaluation -> buildJsonObject {
        put("node_id", evaluation.nodeId.name); put("block", evaluation.block.publicJson()); put("validation", evaluation.result.publicJson())
    } }))
    put("excluded_candidates", JsonArray(evaluations.filter { it.result is BlockValidation.Invalid }.map { it.block.blockHash }.distinct().map { JsonPrimitive(it.hex) }))
    put("winner", winner?.publicJson() ?: JsonNull)
}
