package app

import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import report.WriteResult

object Cli {
    fun defaultOutputRoot(): Path = Path.of(System.getenv("LOCALAPPDATA") ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString(), "ConsoleBlockchain", "sessions")

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        var mode: String? = null
        var root = defaultOutputRoot()
        var outputSpecified = false
        var index = 0
        fun invalid(message: String): Int { err.println(message); err.println(usage); return 2 }
        while (index < args.size) {
            when (val argument = args[index++]) {
                "--demo", "--experiments" -> {
                    if (mode != null) return invalid("Select one scenario")
                    mode = argument.removePrefix("--")
                }
                "--output-dir" -> {
                    if (outputSpecified || index >= args.size || args[index].startsWith("--")) return invalid("--output-dir requires one directory")
                    root = try { Path.of(args[index++]) } catch (_: InvalidPathException) { return invalid("Invalid output directory") }
                    outputSpecified = true
                }
                "--help", "-h" -> { out.println(usage); return 0 }
                else -> return invalid("Unknown argument: $argument")
            }
        }
        if (mode == null) { out.println(usage); return 0 }
        val session = LabSession(root, mode)
        val results = ControlScenario.run(session)
        results.forEach { out.println("${if (it.passed) "PASS" else "FAIL"} ${it.name}: ${it.actual}") }
        out.println("Materials: ${session.reports.directory.toAbsolutePath()}")
        val saved = session.export()
        if (saved is WriteResult.Failed) err.println("Export failed: ${saved.reason}; public results retained in memory")
        return if (results.all { it.passed } && saved == WriteResult.Saved) 0 else 1
    }

    private const val usage = "ConsoleBlockchain [--demo | --experiments] [--output-dir DIRECTORY] [--help]"
}
