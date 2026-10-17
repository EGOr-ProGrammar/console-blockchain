package crypto

import kotlinx.serialization.json.*

object CanonicalJson : CanonicalEncoder {
    override fun encode(value: JsonElement): ByteArray = canonical(value).toString().toByteArray(Charsets.UTF_8)

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { (key, child) ->
            requireAscii(key)
            canonical(child)
        })
        is JsonArray -> JsonArray(value.map(::canonical))
        is JsonPrimitive -> {
            require(value != JsonNull) { "null is outside the canonical domain" }
            if (value.isString) {
                requireAscii(value.content)
            } else {
                require(value.content.matches(Regex("0|[1-9][0-9]*")) && value.content.toLongOrNull() != null) {
                    "Expected a nonnegative Long"
                }
            }
            value
        }
    }

    private fun requireAscii(value: String) {
        require(value.all { it.code <= 127 }) { "Canonical strings must be ASCII" }
    }
}
