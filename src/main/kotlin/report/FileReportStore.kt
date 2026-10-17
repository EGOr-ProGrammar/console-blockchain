package report

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.time.Instant
import java.util.Properties
import java.util.UUID

class FileReportStore(private var root: Path, private val type: String) : ReportStore {
    private val id = UUID.randomUUID().toString()
    private val events = mutableListOf<LabEvent>()
    private val started = Instant.now().toString()
    private val pretty = Json { prettyPrint = true }
    val directory: Path get() = root.resolve(id)

    override fun record(operation: String, result: JsonObject): WriteResult {
        events += LabEvent(events.size.toLong() + 1, operation, Json.parseToJsonElement(result.toString()).jsonObject)

        return export()
    }

    override fun export(root: Path?): WriteResult {
        if (root != null) this.root = root

        return try {
            Files.createDirectories(directory)
            materials().forEach { (name, content) -> writeAtomically(name, content) }
            WriteResult.Saved
        } catch (error: IOException) {
            WriteResult.Failed("${error.javaClass.simpleName}: ${error.message}")
        } catch (error: SecurityException) {
            WriteResult.Failed("${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun snapshot(): SessionReport = SessionReport(id, type, events)

    private fun materials(): Map<String, String> {
        val candidates = candidateRows()
        val balances = balanceRows()
        val experiments = experimentRows()

        return linkedMapOf(
            "metadata.json" to pretty.encodeToString(metadata()),
            "events.jsonl" to events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n") { Json.encodeToString(it) },
            "journal.txt" to events.joinToString("\n") { "Step ${it.step}: ${it.operation}\n${pretty.encodeToString(it.result)}\n" },
            "summary.md" to summary(candidates, balances, experiments),
            "candidates.csv" to csv(candidates),
            "balances.csv" to csv(balances),
            "experiments.csv" to csv(experiments),
        )
    }

    private fun metadata(): JsonObject = buildJsonObject {
        put("session_id", id)
        put("session_type", type)
        put("started_at", started)
        put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        put("java_runtime", "${System.getProperty("java.runtime.version")} ${System.getProperty("java.vm.name")}")
        val versions = Properties().apply { FileReportStore::class.java.getResourceAsStream("/build-info.properties")?.use(::load) }
        put("versions", buildJsonObject { versions.stringPropertyNames().sorted().forEach { put(it, versions.getProperty(it)) } })
    }

    private fun writeAtomically(name: String, content: String) {
        val temporary = Files.createTempFile(directory, ".$name-", ".tmp")

        try {
            Files.writeString(temporary, content, UTF_8)

            try {
                Files.move(temporary, directory.resolve(name), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (
                _: AtomicMoveNotSupportedException,
            ) {
                Files.move(temporary, directory.resolve(name), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun experimentRows(): List<List<String>> = listOf(listOf("step", "name", "expected", "actual", "passed")) +
        events.filter { it.operation == "experiment" }.map { event ->
            listOf(event.step.toString()) +
                listOf("name", "expected", "actual", "passed").map { event.result[it].text() }
        }

    private fun balanceRows(): List<List<String>> = buildList {
        add(listOf("step", "phase", "name", "address", "balance", "next_nonce"))
        for (event in events) {
            for (phase in listOf("before", "after")) {
                val state = event.result[phase] as? JsonObject ?: continue
                val accounts = state["accounts"] as? JsonArray ?: continue
                for (account in accounts) {
                    add(
                        listOf(event.step.toString(), phase) +
                            listOf("name", "address", "balance", "next_nonce").map { account.jsonObject[it].text() },
                    )
                }
            }
        }
    }

    private fun candidateRows(): List<List<String>> {
        val event = events.lastOrNull { it.operation == "candidates" }
        val candidates = (event?.result?.get("candidates") as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val winner =
            events
                .lastOrNull { it.operation == "consensus" && it.result["status"].text() == "ACCEPTED" }
                ?.result
                ?.get("winner")
                ?.jsonObject
                ?.get("block_hash")
                .text()

        return buildList {
            add(listOf("field") + candidates.indices.map { "B${it + 1}" })
            for (field in listOf("index", "prev_hash", "producer", "merkle_root", "block_hash", "verify_block", "reason", "accepted")) {
                add(
                    listOf(field) +
                        candidates.map { candidate ->
                            candidateCell(candidate, field, winner)
                        },
                )
            }
        }
    }

    private fun candidateCell(candidate: JsonObject, field: String, winner: String): String {
        val block = candidate.getValue("block").jsonObject
        val validation = candidate.getValue("validation").jsonObject

        return when (field) {
            "block_hash" -> block[field].text()
            "verify_block" -> validation["status"].text()
            "reason" -> (validation["error"] as? JsonObject)?.get("message").text()
            "accepted" -> (block["block_hash"].text() == winner).toString()
            else -> block.getValue("header").jsonObject[field].text()
        }
    }

    private fun summary(
        candidates: List<List<String>>,
        balances: List<List<String>>,
        experiments: List<List<String>>,
    ): String = buildString {
        appendLine("# Материалы лабораторной работы 2")
        appendLine(
            "Сессия $id ($type), $started. Учебный блокчейн в одном процессе: Ed25519, SHA-256, дерево Меркла и голосование трёх узлов.",
        )
        appendLine(
            "Адрес — SHA-256 от 32 raw-байт публичного ключа. Подпись относится к UTF-8 компактного JSON тела с сортировкой ключей. Закрытые ключи остаются в памяти компонента подписи.",
        )
        appendLine("## Кандидаты")
        appendLine(markdown(candidates))
        appendLine("## Балансы и nonce")
        appendLine(markdown(balances))
        appendLine("## Проверочные эксперименты")
        appendLine(markdown(experiments))
        appendLine("## Полный протокол публичных результатов")
        for (event in events) {
            appendLine("### Шаг ${event.step}: ${event.operation}")

            if (event.operation == "merkle") {
                val tree = event.result["tree"] as? JsonObject
                (tree?.get("levels") as? JsonArray)?.forEach { level ->
                    appendLine(
                        "Уровень ${level.jsonObject["level"]}: дублирован последний узел = ${level.jsonObject["duplicated_last"]}",
                    )
                    level.jsonObject["hashes"]?.jsonArray?.forEach { appendLine("- `${it.jsonPrimitive.content}`") }
                }
            }
            appendLine("```json\n${pretty.encodeToString(event.result)}\n```")
        }
        appendLine(
            "Подписи обнаруживают подмену авторства и данных; дерево связывает порядок записей с заголовком; фиксация состояния выполняется после кворума. Выбор минимального хэша — учебное правило, не защищённый промышленный консенсус.",
        )
    }

    private fun csv(rows: List<List<String>>): String = "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::csvCell) }

    private fun csvCell(value: String): String {
        val first = value.trimStart().firstOrNull()
        val unsafe = value.firstOrNull()?.isISOControl() == true || first != null && first in "=+-@＝＋－＠"
        // CSV is for Excel viewing; JSON retains the exact value for programmatic use.
        return "\"${if (unsafe) "\t" else ""}${value.replace("\"", "\"\"")}\""
    }

    private fun markdown(rows: List<List<String>>): String = if (rows.isEmpty()) {
        ""
    } else {
        (listOf(rows.first(), List(rows.first().size) { "---" }) + rows.drop(1)).joinToString("\n") { row ->
            "| " +
                row.joinToString(" | ") { it.replace("|", "\\|").replace("\n", "<br>") } +
                " |"
        }
    }
}

private fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}
