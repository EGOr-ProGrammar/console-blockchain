package report

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileReportStoreTest {
    @TempDir
    lateinit var root: Path
    private val required = setOf(
        "metadata.json",
        "events.jsonl",
        "journal.txt",
        "summary.md",
        "candidates.csv",
        "balances.csv",
        "experiments.csv"
    )

    @Test
    fun `each operation writes complete materials and independent sessions never overwrite`() {
        val first = FileReportStore(root, "manual")
        val second = FileReportStore(root, "manual")
        assertEquals(WriteResult.Saved, first.record("rejected", buildJsonObject { put("reason", "SIGNATURE") }))
        assertEquals(WriteResult.Saved, second.record("created", buildJsonObject { put("reason", "ok") }))
        assertNotEquals(first.directory, second.directory)
        for (store in listOf(first, second)) {
            assertEquals(
                required,
                Files.list(store.directory).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() })
            val metadata =
                Json.parseToJsonElement(Files.readString(store.directory.resolve("metadata.json"))).jsonObject
            assertEquals(store.snapshot().id, metadata.getValue("session_id").jsonPrimitive.content)
            assertTrue(metadata.containsKey("versions"))
            val lines = Files.readAllLines(store.directory.resolve("events.jsonl"))
            assertEquals(1, lines.size)
            assertEquals(1L, Json.parseToJsonElement(lines.single()).jsonObject.getValue("step").jsonPrimitive.long)
        }
        assertTrue(Files.readString(first.directory.resolve("journal.txt")).contains("SIGNATURE"))
        assertTrue(Files.readString(first.directory.resolve("summary.md")).contains("SIGNATURE"))
    }

    @Test
    fun `write failure retains public events for later complete export`() {
        val file = root.resolve("file")
        Files.writeString(file, "not a directory")
        val store = FileReportStore(file, "manual")
        val result = assertIs<WriteResult.Failed>(store.record("accepted", buildJsonObject { put("balance", 95) }))
        assertTrue(result.reason.isNotBlank())
        assertEquals(1, store.snapshot().events.size)
        val snapshot = store.snapshot()
        store.record("rejected", buildJsonObject { put("reason", "NONCE") })
        assertEquals(1, snapshot.events.size)
        assertEquals(WriteResult.Saved, store.export(root.resolve("retry")))
        assertEquals(2, Files.readAllLines(store.directory.resolve("events.jsonl")).size)
    }

    @Test
    fun `CSV includes UTF8 BOM and escapes quotes commas and multiline values`() {
        val store = FileReportStore(root, "experiments")
        store.record("experiment", buildJsonObject {
            put("name", "quoted, \"value\"\nnext")
            put("expected", "отказ")
            put("actual", "SIGNATURE")
            put("passed", true)
        })
        for (name in listOf("candidates.csv", "balances.csv", "experiments.csv")) {
            assertContentEquals(
                byteArrayOf(-17, -69, -65),
                Files.readAllBytes(store.directory.resolve(name)).take(3).toByteArray()
            )
        }
        val csv = Files.readString(store.directory.resolve("experiments.csv"))
        assertTrue(csv.contains("\"quoted, \"\"value\"\"\nnext\""))
        assertTrue(csv.contains("отказ"))
        assertTrue(csv.contains("SIGNATURE"))
    }

    @Test
    fun `CSV neutralizes spreadsheet formulas while JSON keeps original public data`() {
        val store = FileReportStore(root, "experiments")
        val inputs = listOf(
            "=1+1",
            "+1+1",
            "-1+1",
            "@SUM(1)",
            "\t=1+1",
            "\r=1+1",
            "\n=1+1",
            "  =1+1",
            "＝1+1",
            "＋1+1",
            "－1+1",
            "＠SUM(1)"
        )
        for (input in inputs) {
            assertEquals(WriteResult.Saved, store.record("experiment", buildJsonObject {
                put("name", input); put("expected", "text"); put("actual", input); put("passed", true)
            }))
        }
        val csv = Files.readString(store.directory.resolve("experiments.csv"))
        for (input in inputs) assertTrue(csv.contains("\"\t$input\""), input)
        val events = Files.readAllLines(store.directory.resolve("events.jsonl"))
        assertEquals(
            inputs,
            events.map { Json.parseToJsonElement(it).jsonObject.getValue("result").jsonObject.getValue("name").jsonPrimitive.content })
    }
}
