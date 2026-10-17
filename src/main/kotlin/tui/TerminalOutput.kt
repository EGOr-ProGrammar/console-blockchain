package tui

import java.io.PrintStream

/** Mosaic switches the Windows console to UTF-8 after System.out has chosen its encoding. */
internal fun <T> withUtf8Stdout(action: () -> T): T {
    val previous = System.out
    val utf8 = PrintStream(previous, true, Charsets.UTF_8)
    System.setOut(utf8)

    return try {
        action()
    } finally {
        utf8.flush()
        System.setOut(previous)
    }
}
