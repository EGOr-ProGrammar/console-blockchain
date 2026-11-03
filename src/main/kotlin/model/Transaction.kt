package model

import kotlinx.serialization.Serializable

/** Поля, которые сериализуются и подписываются отправителем. */
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

/** Подписанное тело с его SHA-256 идентификатором и подписью Ed25519. */
@Serializable
data class SignedTransaction(val body: TransactionBody, val txId: Hash256, val signature: SignatureHex)
