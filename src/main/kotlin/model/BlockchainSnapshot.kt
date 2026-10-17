package model

class BlockchainSnapshot(
    val genesis: Genesis,
    chain: List<Block>,
    val ledger: LedgerSnapshot,
    pending: List<SignedTransaction>,
) {
    val chain: List<Block> = frozenList(chain)
    val pending: List<SignedTransaction> = frozenList(pending)
    val tip: Block get() = chain.last()
}
