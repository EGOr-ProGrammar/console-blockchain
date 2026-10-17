package model

import kotlinx.serialization.Serializable

@Serializable
data class WalletInfo(val name: String, val publicKey: PublicKeyHex, val address: Address)
