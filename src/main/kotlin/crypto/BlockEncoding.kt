package crypto

import kotlinx.serialization.json.*
import model.*

fun BlockHeader.canonicalObject(): JsonObject = buildJsonObject {
    put("index", index)
    put("prev_hash", prevHash.hex)
    put("producer", producer)
    put("merkle_root", merkleRoot.hex)
}

fun Genesis.canonicalHeader(): JsonObject = JsonObject(
    block.header.canonicalObject() +
        mapOf(
            "allocations" to
                JsonArray(
                    allocations.map { allocation ->
                        buildJsonObject {
                            put("address", allocation.address.hex)
                            put("amount", allocation.amount)
                        }
                    },
                ),
        ),
)
