package tui

import androidx.compose.runtime.DisposableEffect
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.runMosaicBlocking
import report.WriteResult
import java.io.PrintStream
import java.nio.file.Path

object TuiMain {
    fun run(root: Path, err: PrintStream = System.err): Int {
        val controller = TuiController(root)
        val interactive = runMosaicBlocking(NonInteractivePolicy.Return) {
            DisposableEffect(Unit) {
                print("\u001b[?1049h\u001b[H")
                onDispose { print("\u001b[?1049l") }
            }
            BlockchainScreen(controller, noColor = System.getenv("NO_COLOR") != null)
        }
        if (!interactive) {
            err.println("Для TUI нужен интерактивный терминал. Используйте --demo или --experiments.")
            return 1
        }
        val saved = controller.session.export()
        println("Материалы: ${controller.session.reports.directory.toAbsolutePath()}")
        if (saved is WriteResult.Failed) err.println("Ошибка экспорта: ${saved.reason}")
        return if (saved == WriteResult.Saved) 0 else 1
    }
}
