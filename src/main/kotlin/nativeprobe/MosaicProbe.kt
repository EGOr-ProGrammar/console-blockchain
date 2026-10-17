package nativeprobe

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import kotlinx.coroutines.awaitCancellation
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Text

fun main() {
    runMosaicBlocking { ProbeScreen {} }
}

@Composable
fun ProbeScreen(onExit: () -> Unit) {
    var key by remember { mutableStateOf("none") }
    var running by remember { mutableStateOf(true) }
    val size = LocalTerminalState.current.size
    if (running) LaunchedEffect(Unit) { awaitCancellation() }
    Text(
        if (running) "Mosaic probe ${size.columns}x${size.rows} key=$key; Esc exits" else "Closed",
        modifier = Modifier.onKeyEvent { event ->
            key = event.key
            if (event.key == "Escape") { running = false; onExit() }
            true
        },
    )
}
