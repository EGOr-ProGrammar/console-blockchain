package nativeprobe

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class MosaicProbeTest {
    @Test
    fun `probe reacts to keys resize and escape`() = runTest {
        runMosaicTest {
            var exited = false
            val first = setContentAndSnapshot { ProbeScreen { exited = true } }
            assertTrue(first.contains("80x24"))
            sendKeyEvent(KeyboardEvent('a'.code))
            assertTrue(awaitSnapshot().contains("key=a"))
            state.size.value = Terminal.Size(120, 40)
            val resized = awaitSnapshot()
            assertTrue(resized.contains("120x40"))
            assertTrue(resized.contains("key=a"))
            sendKeyEvent(KeyboardEvent(27))
            awaitSnapshot()
            assertTrue(exited)
        }
    }
}
