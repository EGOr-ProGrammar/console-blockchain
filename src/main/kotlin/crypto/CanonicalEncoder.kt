package crypto

import kotlinx.serialization.json.JsonElement

/** Compact UTF-8 JSON, recursively sorted keys; ASCII strings and nonnegative Longs only. */
interface CanonicalEncoder {
    fun encode(value: JsonElement): ByteArray
}
