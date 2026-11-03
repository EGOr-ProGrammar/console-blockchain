package model

/** Снимок цепочки, подтверждённых счетов и очереди на момент проверки. */
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
