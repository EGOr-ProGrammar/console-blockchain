package ledger

import crypto.*
import model.*

object Merkle {
    fun build(transactions: List<SignedTransaction>): MerkleTree {
        require(transactions.size in 1..3) { "A block has 1–3 transactions" }
        var hashes = transactions.map { JdkCryptography.sha256(CanonicalJson.encode(it.canonicalObject())) }
        val levels = mutableListOf<MerkleLevel>()
        while (hashes.size > 1) {
            val duplicate = hashes.size % 2 != 0
            val padded = if (duplicate) hashes + hashes.last() else hashes
            levels += MerkleLevel(padded, duplicate)
            hashes = padded.chunked(2).map { (left, right) -> JdkCryptography.sha256(left.hex.hexBytes() + right.hex.hexBytes()) }
        }
        levels += MerkleLevel(hashes, false)
        return MerkleTree(levels, hashes.single())
    }
}
