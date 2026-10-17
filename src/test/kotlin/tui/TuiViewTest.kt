package tui

import com.jakewharton.mosaic.layout.KeyEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class TuiViewTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `only the panel receiving keys has a highlighted cursor and focus border`() {
        val controller = TuiController(root)
        fun frame() = TuiView.render(controller, 160, 30)
        fun focusTitles(value: UiFrame) = value.lines.flatMap { it.spans }.filter { it.text.contains("[фокус]") }
        val work = frame()
        assertEquals(1, focusTitles(work).size)
        assertTrue(focusTitles(work).single().text.contains("Кошельки"))
        assertTrue(work.lines.flatMap { it.spans }.any { it.text.contains("> A") && it.ink.name == "FOCUS" })
        controller.handle(KeyEvent("F2"))
        val menu = frame()
        assertEquals(1, focusTitles(menu).size)
        assertTrue(focusTitles(menu).single().text.contains("Разделы"))
        assertFalse(menu.lines.flatMap { it.spans }.any { it.text.contains("> A") })
        assertTrue(menu.lines.flatMap { it.spans }.any { it.text.contains("> Кошельки") && it.ink.name == "FOCUS" })
        controller.handle(KeyEvent("Enter"))
        controller.handle(KeyEvent("Enter"))
        val details = frame()
        assertEquals(1, focusTitles(details).size)
        assertTrue(focusTitles(details).single().text.contains("Подробности"))
        assertFalse(details.lines.flatMap { it.spans }.any { it.text.contains("> A") })
    }

    @Test
    fun `list focus has one cursor and follows the selected row when scrolling`() {
        val controller = TuiController(root).apply { section = Section.EXPERIMENTS }
        controller.handle(KeyEvent("Enter"))
        controller.focus = 1
        repeat(12) { controller.handle(KeyEvent("ArrowDown")) }
        val frame = TuiView.render(controller, 80, 24)
        val cursors = frame.lines.flatMap { it.spans }.filter { it.ink.name == "FOCUS" && it.text.contains(">") }
        assertEquals(1, cursors.size)
        assertTrue(cursors.single().text.contains("control_balances"))
        assertTrue(controller.bodyOffset > 0)
    }

    @Test
    fun `status header groups chain queue and wallet balances while leaving a terminal row free`() {
        val controller = TuiController(root)
        for (columns in listOf(80, 100, 160)) {
            val frame = TuiView.render(controller, columns, 24)
            val header = frame.lines.take(4).joinToString("\n") { it.plain }
            assertTrue(header.contains("ConsoleBlockchain") && header.contains("Сессия"))
            assertTrue(header.contains("Высота цепочки: 0") && header.contains("В очереди: 0"))
            assertTrue(header.contains("A: 100") && header.contains("B: 100") && header.contains("C: 100"))
            assertTrue(frame.lines.first().plain.contains("┌") && frame.lines[3].plain.contains("└"))
            assertEquals(23, frame.lines.size)
        }
    }

    @Test
    fun `layout uses exact breakpoints and always fits terminal cells`() {
        val controller = TuiController(root)
        for (columns in listOf(40, 79, 80, 99, 100, 139, 140, 200)) for (rows in listOf(10, 23, 24, 40)) {
            val frame = TuiView.render(controller, columns, rows)
            assertTrue(frame.lines.size < rows, "$columns x $rows")
            assertTrue(frame.lines.all { it.plain.length <= columns }, "$columns x $rows")
            if (columns < 80 || rows < 24) {
                assertTrue(frame.lines.any { it.plain.contains("${columns}x$rows") })
            } else {
                assertEquals(if (columns < 100) 0 else 22, frame.menuWidth)
                assertEquals(columns >= 140, frame.splitDetails)
                assertTrue(frame.lines.any { it.plain.contains("Кошельки") })
            }
        }
        assertEquals(1, controller.session.reports.snapshot().events.size)
    }

    @Test
    fun `details wrap full hashes and scroll while form selection and input survive resize`() {
        val controller = TuiController(root)
        controller.section = Section.JOURNAL
        controller.detailsOpen = true
        val first = TuiView.render(controller, 80, 24)
        assertTrue(controller.scrollMax > 0)
        controller.handle(KeyEvent("PageDown"))
        assertNotEquals(first.lines.map { it.plain }, TuiView.render(controller, 80, 24).lines.map { it.plain })
        val key = controller.session.blockchain.wallets[0].publicKey.hex
        controller.section = Section.WALLETS
        val wide = TuiView.render(controller, 220, 40)
        assertTrue(wide.lines.any { it.plain.contains(key) })
        controller.detailsOpen = false
        controller.section = Section.TRANSFERS
        controller.focus = 2
        controller.amount.value = "1".repeat(200)
        controller.amount.cursor = 200
        val form = TuiView.render(controller, 80, 24)
        assertTrue(form.lines.any { it.plain.contains("|") && it.plain.contains("11111") })
        TuiView.render(controller, 79, 23)
        TuiView.render(controller, 140, 24)
        assertEquals(2, controller.focus)
        assertEquals("1".repeat(200), controller.amount.value)
    }

    @Test
    fun `all seven sections show actions empty states and no-color selection words`() {
        val controller = TuiController(root)
        for (section in Section.entries) {
            controller.section = section
            val frame = TuiView.render(controller, 100, 24)
            val text = frame.lines.joinToString("\n") { it.plain }
            assertTrue(text.contains(section.title), section.name)
            assertTrue(text.contains("Tab") && text.contains("F2") && text.contains("Esc"))
        }
        controller.section = Section.BLOCKS
        val text = TuiView.render(controller, 80, 24).lines.joinToString("\n") { it.plain }
        assertTrue(text.contains("недоступно"))
        assertTrue(text.contains(">"))
    }
}
