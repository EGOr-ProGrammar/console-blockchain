package tui

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import kotlinx.coroutines.awaitCancellation

@Composable
fun BlockchainScreen(controller: TuiController, noColor: Boolean = false, onExit: () -> Unit = {}) {
    var revision by remember { mutableIntStateOf(0) }
    val size = LocalTerminalState.current.size
    if (!controller.quit) {
        LaunchedEffect(Unit) { awaitCancellation() }
        val frame = remember(revision, size) { TuiView.render(controller, size.columns, size.rows) }
        Column(Modifier.onKeyEvent { event ->
            controller.handle(event, size.columns, size.rows)
            revision++
            if (controller.quit) onExit()
            true
        }) {
            frame.lines.forEach { row -> Row {
                row.spans.forEach { span -> Text(
                    span.text,
                    color = if (noColor) Color.Unspecified else TuiTheme.ink(span.ink),
                    background = if (noColor) Color.Unspecified else if (span.surface) TuiTheme.surface else TuiTheme.background,
                    textStyle = if (!noColor && span.bold) TextStyle.Bold else TextStyle.Empty,
                ) }
            } }
        }
    } else Text("Сессия завершена")
}

object TuiTheme {
    val background = rgb(0x0F172A)
    val surface = rgb(0x1E293B)
    fun ink(ink: Ink): Color = rgb(when (ink) {
        Ink.TEXT -> 0xE2E8F0; Ink.MUTED -> 0x94A3B8; Ink.BORDER -> 0x475569; Ink.ACCENT -> 0x38BDF8
        Ink.SUCCESS -> 0x4ADE80; Ink.WAITING -> 0xFBBF24; Ink.ERROR -> 0xFB7185
    })
    private fun rgb(value: Int) = Color((value shr 16) and 255, (value shr 8) and 255, value and 255)
}
