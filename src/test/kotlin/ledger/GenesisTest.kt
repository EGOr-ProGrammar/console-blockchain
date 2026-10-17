package ledger

import crypto.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.*

class GenesisTest {
    private val signer = JdkWalletSigner()
    private val wallets = listOf("A", "B", "C").map(signer::createWallet)

    @Test
    fun `genesis uses exact zero fields sorted allocations and canonical header hash`() {
        val genesis = GenesisRules.create(wallets.reversed())
        assertEquals(BlockHeader(0, Hash256.ZERO, "GENESIS", Hash256.ZERO), genesis.block.header)
        assertTrue(genesis.block.transactions.isEmpty())
        assertEquals(wallets.map { it.address }.sortedBy { it.hex }, genesis.allocations.map { it.address })
        val allocations = wallets.sortedBy { it.address.hex }.joinToString(",") { """{"address":"${it.address.hex}","amount":100}""" }
        val header = """{"allocations":[$allocations],"index":0,"merkle_root":"${Hash256.ZERO.hex}","prev_hash":"${Hash256.ZERO.hex}","producer":"GENESIS"}"""
        val expected = MessageDigest.getInstance("SHA-256").digest(header.toByteArray()).toHex()
        assertEquals(expected, genesis.block.blockHash.hex)
        assertEquals(ValidationResult.Valid, GenesisRules.validate(genesis))
    }

    @Test
    fun `initial balances and nonce derive from genesis and public wallets have no secret material`() {
        val genesis = GenesisRules.create(wallets)
        val state = GenesisRules.initialLedger(genesis)
        assertEquals(wallets.map { it.address }.toSet(), state.accounts.keys)
        assertTrue(state.accounts.values.all { it.balance == 100L && it.nextNonce == 0L })
        val publicJson = Json.encodeToString(wallets)
        assertFalse(publicJson.contains("private", ignoreCase = true))
        assertFalse(publicJson.contains("seed", ignoreCase = true))
    }

    @Test
    fun `genesis validator detects changed allocations and malformed zero fields`() {
        val genesis = GenesisRules.create(wallets)
        fun invalid(value: Genesis) = assertIs<ValidationResult.Invalid>(GenesisRules.validate(value))
        invalid(Genesis(genesis.block, genesis.allocations.mapIndexed { i, a -> if (i == 0) a.copy(amount = 101) else a }))
        invalid(Genesis(genesis.block, genesis.allocations.reversed()))
        invalid(Genesis(genesis.block, genesis.allocations.take(2)))
        invalid(Genesis(genesis.block, List(3) { genesis.allocations[0] }))
        invalid(Genesis(genesis.block.copy(header = genesis.block.header.copy(index = 1)), genesis.allocations))
        invalid(Genesis(genesis.block.copy(header = genesis.block.header.copy(prevHash = Hash256("a".repeat(64)))), genesis.allocations))
        invalid(Genesis(genesis.block.copy(header = genesis.block.header.copy(merkleRoot = Hash256("a".repeat(64)))), genesis.allocations))
        invalid(Genesis(genesis.block.copy(header = genesis.block.header.copy(producer = "P1")), genesis.allocations))
        invalid(Genesis(genesis.block.copy(blockHash = Hash256.ZERO), genesis.allocations))
    }
}
