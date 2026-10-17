package tui

import com.jakewharton.mosaic.layout.KeyEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class TuiControllerTest {
    @TempDir lateinit var root: Path
    private fun TuiController.key(name: String, shift: Boolean = false, ctrl: Boolean = false) = handle(KeyEvent(name, shift = shift, ctrl = ctrl))
    private fun TuiController.choose(target: Section) {
        key("F2")
        repeat((target.ordinal - section.ordinal + Section.entries.size) % Section.entries.size) { key("ArrowDown") }
        key("Enter")
        assertEquals(target, section)
    }

    @Test
    fun `F2 navigation and ordinary viewing never create public operation events`() {
        val controller = TuiController(root)
        controller.choose(Section.JOURNAL)
        controller.key("ArrowDown"); controller.key("PageDown"); controller.key("Enter"); controller.key("Escape")
        controller.key("F1"); controller.key("F1")
        assertFalse(controller.quit)
        assertEquals(1, controller.session.reports.snapshot().events.size)
    }

    @Test
    fun `form text supports editing without stealing letters for navigation and survives small terminal`() {
        val controller = TuiController(root)
        controller.choose(Section.SIGNATURES)
        controller.key("a", ctrl = true)
        for (character in "qedf") controller.key(character.toString())
        assertEquals("qedf", controller.originalMessage.value)
        assertFalse(controller.quit)
        assertEquals(Section.SIGNATURES, controller.section)
        controller.key("ArrowLeft"); controller.key("Backspace")
        assertEquals("qef", controller.originalMessage.value)
        controller.handle(KeyEvent("x"), 79, 23)
        assertEquals("qef", controller.originalMessage.value)
        controller.key("Tab"); controller.key("Tab", shift = true)
        assertEquals(0, controller.focus)
        controller.key("Tab"); controller.key("Tab"); controller.key("Enter")
        assertEquals("message_signature", controller.session.reports.snapshot().events.last().operation)
        val eventCount = controller.session.reports.snapshot().events.size
        controller.key("Tab"); controller.key("Enter")
        assertTrue(controller.detailsOpen)
        assertEquals(eventCount, controller.session.reports.snapshot().events.size)
    }

    @Test
    fun `transfer form reaches pending and block actions preserve state on no quorum then commit winner`() {
        val controller = TuiController(root)
        controller.choose(Section.TRANSFERS)
        fun transfer(senderSteps: Int, recipientSteps: Int, amount: String) {
            controller.focus = 0
            repeat(senderSteps) { controller.key("ArrowRight") }
            controller.key("Tab")
            repeat(recipientSteps) { controller.key("ArrowRight") }
            controller.key("Tab"); controller.key("a", ctrl = true)
            for (character in amount) controller.key(character.toString())
            controller.key("Tab"); controller.key("Enter")
        }
        transfer(0, 0, "10")
        transfer(1, 1, "20")
        transfer(1, 1, "5")
        assertEquals(3, controller.session.blockchain.snapshot().pending.size)
        controller.choose(Section.BLOCKS)
        controller.key("Enter")
        assertEquals(3, controller.session.candidates.size)
        controller.key("Tab"); controller.key("Tab"); controller.key("Enter")
        assertEquals(1, controller.session.blockchain.snapshot().chain.size)
        assertTrue(controller.status.contains("NO_QUORUM"))
        controller.key("Tab", shift = true); controller.key("Enter")
        val snapshot = controller.session.blockchain.snapshot()
        assertEquals(2, snapshot.chain.size)
        assertTrue(snapshot.pending.isEmpty())
        assertEquals(listOf(95L, 90L, 115L), controller.session.blockchain.wallets.map { snapshot.ledger.accounts.getValue(it.address).balance })
    }

    @Test
    fun `disabled action gives textual reason and experiment action creates separate session`() {
        val controller = TuiController(root)
        controller.choose(Section.BLOCKS)
        controller.key("Enter")
        assertTrue(controller.status.contains("НЕДОСТУПНО"))
        assertEquals(1, controller.session.reports.snapshot().events.size)
        controller.choose(Section.EXPERIMENTS)
        controller.key("Enter")
        assertTrue(controller.status.contains("13/13"))
        assertEquals(1, controller.session.blockchain.snapshot().chain.size)
        assertEquals("experiments_launched", controller.session.reports.snapshot().events.last().operation)
    }

    @Test
    fun `retry export preserves full experimental sessions after filesystem failure`() {
        val blocked = root.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val controller = TuiController(blocked)
        controller.choose(Section.EXPERIMENTS)
        controller.key("Enter")
        assertTrue(controller.status.contains("ОШИБКА ЭКСПОРТА"))
        controller.choose(Section.JOURNAL)
        controller.focus = 1
        controller.key("a", ctrl = true)
        val retryRoot = root.resolve("retry")
        retryRoot.toString().forEach { controller.key(it.toString()) }
        controller.key("Tab"); controller.key("Enter")
        val directories = Files.list(retryRoot).use { it.toList() }
        assertEquals(2, directories.size)
        val experimental = directories.single {
            Json.parseToJsonElement(Files.readString(it.resolve("metadata.json"))).jsonObject["session_type"]?.jsonPrimitive?.content == "experiments"
        }
        val events = Files.readAllLines(experimental.resolve("events.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(13, events.count { it["operation"]?.jsonPrimitive?.content == "experiment" })
        assertTrue(events.any { it["operation"]?.jsonPrimitive?.content == "candidates" })
    }
}
