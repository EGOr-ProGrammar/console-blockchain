package tui

import com.jakewharton.mosaic.layout.KeyEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class TuiViewTest {
    @TempDir lateinit var root: Path

    @Test
    fun `layout uses exact breakpoints and always fits terminal cells`() {
        val controller = TuiController(root)
        for (columns in listOf(40, 79, 80, 99, 100, 139, 140, 200)) for (rows in listOf(10, 23, 24, 40)) {
            val frame = TuiView.render(controller, columns, rows)
            assertEquals(rows, frame.lines.size, "$columns x $rows")
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
