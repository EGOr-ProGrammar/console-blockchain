package report

import model.frozenList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

@Serializable
data class LabEvent(val step: Long, val operation: String, val result: JsonObject)

class SessionReport(val id: String, val type: String, events: List<LabEvent>) {
    val events = frozenList(events)
}

sealed interface WriteResult {
    data object Saved : WriteResult
    data class Failed(val reason: String) : WriteResult
}

/** Retains all public results in memory even when writing fails; export retries the whole session. */
interface ReportStore {
    fun record(operation: String, result: JsonObject): WriteResult
    fun export(root: Path? = null): WriteResult
    fun snapshot(): SessionReport
}
