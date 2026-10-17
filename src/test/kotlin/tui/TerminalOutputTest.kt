package tui

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

class TerminalOutputTest {
    @Test
    fun `Mosaic output is UTF8 even when Windows stdout starts in a legacy code page`() {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        val legacy = PrintStream(bytes, true, Charset.forName("IBM866"))
        System.setOut(legacy)
        try {
            withUtf8Stdout { print("Кошельки ─ подпись") }
            assertContentEquals("Кошельки ─ подпись".toByteArray(Charsets.UTF_8), bytes.toByteArray())
            assertSame(legacy, System.out)
        } finally {
            System.setOut(original)
        }
    }
}
