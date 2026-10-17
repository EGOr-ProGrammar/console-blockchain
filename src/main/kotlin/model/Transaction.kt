package model

import kotlinx.serialization.Serializable

@Serializable
data class TransactionBody(
    val sender: Address,
    val recipient: Address,
    val amount: Long,
    val nonce: Long,
    val publicKey: PublicKeyHex,
) {
    init {
        require(amount > 0)
        require(nonce >= 0)
    }
}

@Serializable
data class SignedTransaction(val body: TransactionBody, val txId: Hash256, val signature: SignatureHex)
