package tui

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class BlockchainScreenTest {
    @TempDir lateinit var root: Path

    @Test
    fun `Mosaic routes menu focus input resize validation and exit without losing form`() = runTest {
        runMosaicTest {
            val controller = TuiController(root)
            var exited = false
            assertTrue(setContentAndSnapshot { BlockchainScreen(controller, onExit = { exited = true }) }.contains("Кошельки"))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.F2)); assertTrue(awaitSnapshot().contains("Разделы"))
            repeat(2) { sendKeyEvent(KeyboardEvent(KeyboardEvent.Down)) }
            sendKeyEvent(KeyboardEvent(13)); awaitSnapshot()
            assertEquals(Section.TRANSFERS, controller.section)
            repeat(2) { sendKeyEvent(KeyboardEvent(9)) }
            sendKeyEvent(KeyboardEvent('a'.code, modifiers = KeyboardEvent.ModifierCtrl))
            "1.5".forEach { sendKeyEvent(KeyboardEvent(it.code)) }
            assertTrue(awaitSnapshot().contains("1.5|"))
            state.size.value = Terminal.Size(79, 23)
            assertTrue(awaitSnapshot().contains("79x23"))
            sendKeyEvent(KeyboardEvent('9'.code))
            state.size.value = Terminal.Size(140, 40)
            assertTrue(awaitSnapshot().contains("1.5|"))
            assertEquals("1.5", controller.amount.value)
            sendKeyEvent(KeyboardEvent(13)); sendKeyEvent(KeyboardEvent(13))
            assertTrue(awaitSnapshot().contains("ОТКАЗ AMOUNT"))
            assertEquals("transfer", controller.session.reports.snapshot().events.last().operation)
            sendKeyEvent(KeyboardEvent(27)); awaitSnapshot()
            assertTrue(exited)
        }
    }

    @Test
    fun `theme uses exact colors and NO_COLOR leaves selection and statuses readable`() = runTest {
        val strategy = SnapshotStrategy<String> { it.draw().render(AnsiLevel.TRUECOLOR, false) }
        val coloredController = TuiController(root)
        runMosaicTest(strategy) {
            val colored = setContentAndSnapshot { BlockchainScreen(coloredController) }
            assertTrue(colored.contains("38;2;56;189;248"))
            assertTrue(colored.contains("48;2;15;23;42"))
            assertTrue(colored.contains("48;2;30;41;59"))
            assertTrue(Regex("38;2;251;191;36m[^\\n]*> A").containsMatchIn(colored))
        }
        runMosaicTest(strategy) {
            val controller = TuiController(root).apply { section = Section.BLOCKS }
            val plain = setContentAndSnapshot { BlockchainScreen(controller, noColor = true) }
            assertFalse(plain.contains("38;2;"))
            assertFalse(plain.contains("48;2;"))
            assertTrue(plain.contains(">") && plain.contains("недоступно") && plain.contains("ГОТОВО"))
        }
    }
}
