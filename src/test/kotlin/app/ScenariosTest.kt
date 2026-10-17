package app

import kotlinx.serialization.json.*
import model.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import report.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ScenariosTest {
    @TempDir lateinit var root: Path
    private val experiments = setOf("message_signature", "tampered_amount", "invalid_amount", "overspend", "replay", "pending_sender", "merkle_1_2_3", "transaction_order", "damaged_chain", "candidate_conflict", "proposal_order", "no_quorum", "control_balances")

    @Test
    fun `both headless modes export complete evidence and expected control balances without secrets`() {
        val output = ByteArrayOutputStream()
        for (mode in listOf("--demo", "--experiments")) {
            assertEquals(0, Cli.run(arrayOf(mode, "--output-dir", root.toString()), PrintStream(output), PrintStream(output)), output.toString())
        }
        val sessions = Files.list(root).use { it.toList() }
        assertEquals(2, sessions.size)
        for (directory in sessions) {
            val events = Files.readAllLines(directory.resolve("events.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.getValue("step").jsonPrimitive.long })
            fun results(operation: String) = events.filter { it.getValue("operation").jsonPrimitive.content == operation }.map { it.getValue("result").jsonObject }
            assertEquals(experiments, results("experiment").map { it.getValue("name").jsonPrimitive.content }.toSet())
            assertTrue(results("experiment").all { it.getValue("passed").jsonPrimitive.boolean })
            val initial = results("session_started").single()
            assertEquals(3, initial.getValue("wallets").jsonArray.size)
            val signature = results("message_signature").single()
            assertEquals("Blockchainlab 2", signature.getValue("message").jsonPrimitive.content)
            assertEquals("Blockchainlab 3", signature.getValue("changed_message").jsonPrimitive.content)
            assertTrue(signature.getValue("original_valid").jsonPrimitive.boolean)
            assertFalse(signature.getValue("changed_valid").jsonPrimitive.boolean)
            assertFalse(signature.getValue("other_key_valid").jsonPrimitive.boolean)
            val transfers = results("transfer").filter { it.containsKey("transaction") }
            assertTrue(transfers.any { it.getValue("checks").jsonObject.size == 3 })
            val validTransfer = transfers.first()
            assertEquals(setOf("sender", "recipient", "amount", "nonce", "public_key", "tx_id", "signature"), validTransfer.getValue("transaction").jsonObject.keys)
            assertTrue(validTransfer.getValue("message_hex").jsonPrimitive.content.isNotEmpty())
            assertEquals(listOf(1, 2, 3), results("merkle").map { it.getValue("count").jsonPrimitive.int })
            val candidates = results("candidates").single().getValue("candidates").jsonArray
            assertEquals(3, candidates.size)
            assertEquals("SIGNATURE", candidates.last().jsonObject.getValue("validation").jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val consensus = results("consensus")
            val noQuorum = consensus.first { it.getValue("status").jsonPrimitive.content == "NO_QUORUM" }
            assertEquals(noQuorum["before"], noQuorum["after"])
            val accepted = consensus.single { it.getValue("status").jsonPrimitive.content == "ACCEPTED" }
            val after = accepted.getValue("after").jsonObject
            val accounts = after.getValue("accounts").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
            assertEquals(listOf(95L, 90L, 115L), listOf("A", "B", "C").map { accounts.getValue(it).getValue("balance").jsonPrimitive.long })
            assertTrue(accounts.values.all { it.getValue("next_nonce").jsonPrimitive.long == 1L })
            assertTrue(after.getValue("pending").jsonArray.isEmpty())
            assertEquals(2, after.getValue("chain").jsonArray.size)
            assertEquals(3, accepted.getValue("votes").jsonArray.size)
            for (name in listOf("metadata.json", "events.jsonl", "journal.txt", "summary.md", "candidates.csv", "balances.csv", "experiments.csv")) {
                val content = Files.readString(directory.resolve(name))
                assertTrue(content.isNotBlank(), name)
                assertFalse(content.contains("private_key", ignoreCase = true), name)
                assertFalse(content.contains("seed", ignoreCase = true), name)
            }
            assertTrue(Files.readString(directory.resolve("candidates.csv")).contains("\"B3\""))
            assertTrue(Files.readString(directory.resolve("summary.md")).contains("Уровень"))
        }
    }

    @Test
    fun `bad arguments return two and inaccessible output returns one with a reason`() {
        val output = ByteArrayOutputStream()
        for (args in listOf(arrayOf("--unknown"), arrayOf("--demo", "--experiments"), arrayOf("--output-dir"), arrayOf("--demo", "--output-dir", "--experiments"))) {
            assertEquals(2, Cli.run(args, PrintStream(output), PrintStream(output)))
        }
        assertTrue(output.size() > 0)
        val file = root.resolve("file")
        Files.writeString(file, "block writes")
        assertEquals(1, Cli.run(arrayOf("--demo", "--output-dir", file.toString()), PrintStream(output), PrintStream(output)))
        assertTrue(output.toString().contains("Export failed:"))
        assertTrue(output.toString().contains("Exception:"))
    }

    @Test
    fun `filesystem errors do not cancel accepted pending operations and export can be retried`() {
        val file = root.resolve("file")
        Files.writeString(file, "block writes")
        val session = LabSession(file, "manual")
        val wallets = session.blockchain.wallets
        val result = session.transfer(wallets[0].address, wallets[1].address, "10")
        assertEquals(ValidationResult.Valid, result.result)
        assertEquals(1, session.blockchain.snapshot().pending.size)
        assertIs<WriteResult.Failed>(session.lastWrite)
        assertEquals(listOf("session_started", "transfer"), session.reports.snapshot().events.map { it.operation })
        assertEquals(WriteResult.Saved, session.reports.export(root.resolve("retry")))
        assertEquals(2, Files.readAllLines(session.reports.directory.resolve("events.jsonl")).size)
    }
}
