package crypto

import kotlinx.serialization.json.*
import model.*

fun TransactionBody.canonicalObject(): JsonObject = buildJsonObject {
    put("sender", sender.hex)
    put("recipient", recipient.hex)
    put("amount", amount)
    put("nonce", nonce)
    put("public_key", publicKey.hex)
}

fun SignedTransaction.canonicalObject(): JsonObject = JsonObject(
    body.canonicalObject() +
        mapOf(
            "tx_id" to JsonPrimitive(txId.hex),
            "signature" to JsonPrimitive(signature.hex),
        ),
)
