package crypto

import model.*

/** Owns private keys; only public identity and signatures may leave this boundary. */
interface WalletSigner {
    fun createWallet(name: String): WalletInfo
    fun sign(address: Address, message: ByteArray): SignatureHex
}
