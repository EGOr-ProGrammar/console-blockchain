package tui

import app.*
import com.jakewharton.mosaic.layout.KeyEvent
import crypto.canonicalObject
import kotlinx.serialization.json.*
import model.*
import report.*
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class Section(val title: String) {
    WALLETS("Кошельки"),
    SIGNATURES("Подписи"),
    TRANSFERS("Переводы"),
    MERKLE("Меркл"),
    BLOCKS("Блоки"),
    EXPERIMENTS("Эксперименты"),
    JOURNAL("Журнал"),
}

class TextInput(var value: String) {
    var cursor: Int = value.length
    private var selectedAll = false

    fun key(event: KeyEvent): Boolean {
        if (event.ctrl && event.key == "a") {
            selectedAll = true
            return true
        }

        if (event.ctrl || event.alt) return false

        when (event.key) {
            "ArrowLeft" -> {
                selectedAll = false
                if (cursor > 0) cursor = value.offsetByCodePoints(cursor, -1)
            }
            "ArrowRight" -> {
                selectedAll = false
                if (cursor < value.length) cursor = value.offsetByCodePoints(cursor, 1)
            }
            "Home" -> {
                selectedAll = false
                cursor = 0
            }
            "End" -> {
                selectedAll = false
                cursor = value.length
            }
            "Backspace" -> if (!removeSelection() && cursor > 0) {
                val previous = value.offsetByCodePoints(cursor, -1)
                value = value.removeRange(previous, cursor)
                cursor = previous
            }
            "Delete" -> if (!removeSelection() && cursor < value.length) {
                value = value.removeRange(cursor, value.offsetByCodePoints(cursor, 1))
            }
            else -> return insert(event.key)
        }

        return true
    }

    private fun removeSelection(): Boolean {
        if (!selectedAll) return false

        value = ""
        cursor = 0
        selectedAll = false

        return true
    }

    private fun insert(text: String): Boolean {
        if (text.codePointCount(0, text.length) != 1) return false
        if (text.codePointAt(0) < 32 || text == "\u007f") return false

        removeSelection()
        value = value.substring(0, cursor) + text + value.substring(cursor)
        cursor += text.length

        return true
    }
}

class TuiController(outputRoot: Path) {
    val session = LabSession(outputRoot, "manual")
    var section = Section.WALLETS
    var menuOpen = false
    var helpOpen = false
    var detailsOpen = false
    var quit = false
    private val focuses = IntArray(Section.entries.size)
    private val selections = IntArray(Section.entries.size)
    private val bodyScrolls = IntArray(Section.entries.size)
    private val detailScrolls = IntArray(Section.entries.size)
    var menuSelection = 0
    var helpScroll = 0
    var scrollMax = 0
    var revealFocus = true
    var viewportColumns = 0
    var viewportRows = 0
    var bodyOffset: Int
        get() = bodyScrolls[section.ordinal]
        set(value) {
            bodyScrolls[section.ordinal] = value
        }

    var detailOffset: Int
        get() = detailScrolls[section.ordinal]
        set(value) {
            detailScrolls[section.ordinal] = value
        }

    var focus: Int
        get() = focuses[section.ordinal]
        set(value) {
            focuses[section.ordinal] = value
        }

    var selected: Int
        get() = selections[section.ordinal]
        set(value) {
            selections[section.ordinal] = value
        }

    var scroll: Int
        get() = when {
            helpOpen -> helpScroll
            detailsOpen -> detailOffset
            else -> bodyOffset
        }
        set(value) {
            when {
                helpOpen -> helpScroll = value
                detailsOpen -> detailOffset = value
                else -> bodyOffset = value
            }
        }

    var sender = 0
    var recipient = 1
    var leafCount = 1
    var status = "ГОТОВО"
    val amount = TextInput("10")
    val originalMessage = TextInput("Blockchainlab 2")
    val changedMessage = TextInput("Blockchainlab 3")
    val exportRoot = TextInput(outputRoot.toString())
    val details = mutableMapOf<Section, JsonElement>()
    var experiments: List<ExperimentResult> = emptyList()
        private set
    private val experimentSessions = mutableListOf<LabSession>()
    var experimentDirectory: Path? = null
        private set
    val focusCount: Int
        get() =
            when (section) {
                Section.WALLETS -> 1
                Section.SIGNATURES -> 4
                Section.TRANSFERS -> 5
                Section.MERKLE -> 3
                Section.BLOCKS -> 5
                Section.EXPERIMENTS -> 2
                Section.JOURNAL -> 3
            }

    fun handle(event: KeyEvent, columns: Int = 80, rows: Int = 24) {
        revealFocus = event.key !in listOf("PageUp", "PageDown")

        if (event.key == "Escape") {
            when {
                helpOpen -> helpOpen = false
                menuOpen -> menuOpen = false
                detailsOpen -> detailsOpen = false
                else -> quit = true
            }
            return
        }

        if (columns < 80 || rows < 24) return

        if (event.key == "F1") {
            helpOpen = !helpOpen
            menuOpen = false
            return
        }

        if (event.key == "F2") {
            menuOpen = !menuOpen
            helpOpen = false
            menuSelection = section.ordinal
            return
        }

        if (menuOpen) {
            handleMenu(event)
            return
        }

        if (event.key in listOf("PageUp", "PageDown")) {
            scroll =
                (scroll + (if (event.key == "PageUp") -1 else 1) * (rows - 8).coerceAtLeast(1) / 2).coerceIn(
                    0,
                    scrollMax,
                )
            return
        }

        if (helpOpen || detailsOpen) return

        if (event.key == "Tab") {
            focus = (focus + if (event.shift) focusCount - 1 else 1) % focusCount
            return
        }

        val input = focusedInput()

        if (input != null && input.key(event)) return

        if (event.key in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")) {
            val delta = if (event.key in listOf("ArrowLeft", "ArrowUp")) -1 else 1
            when {
                section == Section.TRANSFERS && focus == 0 -> sender = (sender + delta + 3) % 3
                section == Section.TRANSFERS && focus == 1 -> recipient = (recipient + delta + 3) % 3
                section == Section.MERKLE && focus == 0 -> leafCount = (leafCount - 1 + delta + 3) % 3 + 1
                else -> selected = (selected + delta).coerceIn(0, (itemCount() - 1).coerceAtLeast(0))
            }
            return
        }

        if (event.key == "Enter") activate()
    }

    private fun focusedInput(): TextInput? = when {
        section == Section.SIGNATURES && focus == 0 -> originalMessage
        section == Section.SIGNATURES && focus == 1 -> changedMessage
        section == Section.TRANSFERS && focus == 2 -> amount
        section == Section.JOURNAL && focus == 1 -> exportRoot
        else -> null
    }

    private fun handleMenu(event: KeyEvent) {
        when (event.key) {
            "ArrowUp" -> menuSelection = (menuSelection + Section.entries.size - 1) % Section.entries.size
            "ArrowDown" -> menuSelection = (menuSelection + 1) % Section.entries.size
            "Enter" -> {
                section = Section.entries[menuSelection]
                menuOpen = false
                detailsOpen = false
            }
        }
    }

    fun itemCount(): Int = when (section) {
        Section.WALLETS -> 3
        Section.TRANSFERS ->
            session.blockchain
                .snapshot()
                .pending.size
        Section.BLOCKS -> session.candidates.size
        Section.EXPERIMENTS -> experiments.size
        Section.JOURNAL ->
            session.reports
                .snapshot()
                .events.size
        else -> 1
    }

    fun export(root: Path? = null): WriteResult {
        val writes = (listOf(session) + experimentSessions).map { it.export(root) }
        experimentDirectory = experimentSessions.lastOrNull()?.reports?.directory

        return writes.firstOrNull { it is WriteResult.Failed } ?: WriteResult.Saved
    }

    fun detailValue(): JsonElement = when (section) {
        Section.WALLETS -> {
            val wallet = session.blockchain.wallets[selected.coerceIn(0, 2)]
            val account =
                session.blockchain
                    .snapshot()
                    .ledger.accounts
                    .getValue(wallet.address)
            JsonObject(
                wallet.publicJson() +
                    mapOf(
                        "balance" to JsonPrimitive(account.balance),
                        "next_nonce" to JsonPrimitive(account.nextNonce),
                    ),
            )
        }

        Section.TRANSFERS ->
            if (focus == 4) {
                session.blockchain
                    .snapshot()
                    .pending
                    .getOrNull(selected)
                    ?.canonicalObject() ?: JsonObject(emptyMap())
            } else {
                details[section] ?: JsonObject(emptyMap())
            }

        Section.BLOCKS ->
            if (focus == 4) {
                session.reports
                    .snapshot()
                    .events
                    .lastOrNull { it.operation == "candidates" }
                    ?.result
                    ?.get("candidates")
                    ?.jsonArray
                    ?.getOrNull(selected) ?: JsonObject(emptyMap())
            } else {
                details[section]
                    ?: JsonObject(emptyMap())
            }

        Section.EXPERIMENTS -> experiments.getOrNull(selected)?.publicJson() ?: JsonObject(emptyMap())

        Section.JOURNAL ->
            session.reports
                .snapshot()
                .events
                .getOrNull(selected)
                ?.let {
                    buildJsonObject {
                        put("step", it.step)
                        put("operation", it.operation)
                        put("result", it.result)
                    }
                }
                ?: JsonObject(emptyMap())

        else -> details[section] ?: JsonObject(emptyMap())
    }

    private fun activate() {
        when (section) {
            Section.WALLETS -> openDetails()
            Section.SIGNATURES -> activateSignatures()
            Section.TRANSFERS -> activateTransfer()
            Section.MERKLE -> activateMerkle()
            Section.BLOCKS -> activateBlocks()
            Section.EXPERIMENTS ->
                if (focus == 0) {
                    runExperiments()
                } else if (experiments.isNotEmpty()) {
                    openDetails()
                }
            Section.JOURNAL -> activateJournal()
        }
    }

    private fun activateSignatures() {
        if (focus < 2) {
            focus++
        } else if (focus == 3) {
            openDetails()
        } else {
            details[section] = session.signMessage(originalMessage.value, changedMessage.value)
            status = "УСПЕХ: подпись создана; результаты трёх проверок в подробностях"
        }
    }

    private fun activateTransfer() {
        val blockchain = session.blockchain
        val pending = blockchain.snapshot().pending

        when (focus) {
            in 0..2 -> focus++
            3 ->
                validation(
                    session
                        .transfer(
                            blockchain.wallets[sender].address,
                            blockchain.wallets[recipient].address,
                            amount.value,
                        ).result,
                )

            else -> if (pending.isEmpty()) unavailable("очередь пуста") else openDetails()
        }
    }

    private fun activateMerkle() {
        val blockchain = session.blockchain
        val pending = blockchain.snapshot().pending

        if (focus == 0) {
            focus++
        } else if (focus == 2) {
            openDetails()
        } else if (leafCount > pending.size) {
            unavailable("нужно $leafCount pending-записей")
        } else {
            session.merkle(leafCount)
            rememberLast()
            status = "УСПЕХ: дерево для $leafCount записей; полные уровни в подробностях"
        }
    }

    private fun activateBlocks() {
        val pending = session.blockchain.snapshot().pending

        when (focus) {
            0 ->
                if (pending.isEmpty()) {
                    unavailable("сначала создайте переводы")
                } else {
                    session.prepareCandidates()
                    rememberLast()
                    status = "УСПЕХ: кандидаты сформированы и проверены"
                }

            1, 2 ->
                if (session.candidates.isEmpty()) {
                    unavailable("сначала сформируйте кандидатов")
                } else {
                    val result = session.resolve(if (focus == 2) setOf(NodeId.N1, NodeId.N2) else emptySet())
                    rememberLast()
                    status =
                        if (result.winner == null) {
                            "ОЖИДАНИЕ: NO_QUORUM; состояние не изменено"
                        } else {
                            "УСПЕХ: ACCEPTED ${result.winner.blockHash.hex}"
                        }
                }

            3 -> validation(session.verifyChain())
            else -> if (session.candidates.isEmpty()) unavailable("нет кандидатов") else openDetails()
        }
    }

    private fun activateJournal() {
        when (focus) {
            0 -> openDetails()

            1 -> focus++
            else -> {
                val result =
                    try {
                        export(Path.of(exportRoot.value))
                    } catch (error: InvalidPathException) {
                        WriteResult.Failed(error.message ?: "Invalid directory")
                    }

                session.record(
                    "export",
                    buildJsonObject {
                        put("directory", exportRoot.value)
                        put(
                            "status",

                            if (result == WriteResult.Saved) "SAVED" else "FAILED",
                        )

                        if (result is WriteResult.Failed) put("reason", result.reason)
                    },
                )
                status =
                    if (result == WriteResult.Saved) {
                        "УСПЕХ: материалы сохранены"
                    } else {
                        "ОШИБКА ЭКСПОРТА: ${(result as WriteResult.Failed).reason}"
                    }
            }
        }
    }

    private fun unavailable(reason: String) {
        status = "НЕДОСТУПНО: $reason"
    }

    private fun rememberLast() {
        details[section] = session.reports.snapshot().events.last().result
    }

    private fun validation(result: ValidationResult) {
        status =
            when (result) {
                ValidationResult.Valid -> "УСПЕХ: VALID"
                is ValidationResult.Invalid -> "ОТКАЗ ${result.error.code}: ${result.error.message}"
            }

        rememberLast()
    }

    private fun openDetails() {
        detailsOpen = true
        scroll = 0
    }

    private fun runExperiments() {
        val root = session.reports.directory.toAbsolutePath().parent
        val experimentSession = LabSession(root, "experiments")
        experimentSessions += experimentSession
        experiments = ControlScenario(experimentSession).run()
        experimentDirectory = experimentSession.reports.directory

        session.record(
            "experiments_launched",
            buildJsonObject {
                put("session_id", experimentSession.reports.snapshot().id)
                put("directory", experimentDirectory.toString())
                put("results", JsonArray(experiments.map { it.publicJson() }))
            },
        )
        status = "ПРОВЕРКИ: ${experiments.count { it.passed }}/${experiments.size} в отдельной сессии"
        (experimentSession.lastWrite as? WriteResult.Failed)?.let { status = "ОШИБКА ЭКСПОРТА: ${it.reason}" }
    }
}
