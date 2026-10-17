package model

class MerkleLevel(hashes: List<Hash256>, val duplicatedLast: Boolean) {
    val hashes: List<Hash256> = frozenList(hashes)
}

class MerkleTree(levels: List<MerkleLevel>, val root: Hash256) {
    val levels: List<MerkleLevel> = frozenList(levels)
}
