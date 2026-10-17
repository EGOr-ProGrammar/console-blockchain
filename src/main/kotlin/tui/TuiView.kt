package tui

import crypto.canonicalObject
import model.*
import report.*
import kotlinx.serialization.json.*

enum class Ink { TEXT, MUTED, BORDER, ACCENT, SUCCESS, WAITING, ERROR }
data class UiSpan(val text: String, val ink: Ink = Ink.TEXT, val surface: Boolean = false, val bold: Boolean = false)
data class UiLine(val spans: List<UiSpan>, val selected: Boolean = false) {
    val plain: String get() = spans.joinToString("") { it.text }
}
data class UiFrame(val lines: List<UiLine>, val menuWidth: Int, val splitDetails: Boolean)

object TuiView {
    const val outerPadding = 1
    const val innerPadding = 2
    const val panelGap = 2
    const val menuColumns = 22
    private val pretty = Json { prettyPrint = true }

    fun render(controller: TuiController, columns: Int, rows: Int): UiFrame {
        if (columns < 80 || rows < 24) {
            val text = "Нужно 80x24; сейчас ${columns}x$rows. Увеличьте окно. Esc — выход."
            val lines = wrap(text, columns.coerceAtLeast(1)).map { line(it, Ink.WAITING) }
            return UiFrame((lines + List(rows.coerceAtLeast(0)) { line("") }).take(rows.coerceAtLeast(0)), 0, false)
        }
        if (controller.viewportColumns != columns || controller.viewportRows != rows) controller.revealFocus = true
        controller.viewportColumns = columns; controller.viewportRows = rows
        val totalWidth = columns - outerPadding * 2
        val navWidth = if (columns >= 100) menuColumns else 0
        val workWidth = totalWidth - if (navWidth > 0) navWidth + panelGap else 0
        val bodyHeight = rows - 6
        val split = columns >= 140 && !controller.helpOpen && !controller.menuOpen
        val snapshot = controller.session.blockchain.snapshot()
        val wallets = controller.session.blockchain.wallets
        val header = listOf(
            line("ConsoleBlockchain  |  сессия ${controller.session.reports.snapshot().id}", Ink.ACCENT, bold = true),
            line("Высота ${snapshot.tip.header.index}  pending ${snapshot.pending.size}  " + wallets.joinToString("  ") {
                val account = snapshot.ledger.accounts.getValue(it.address)
                "${it.name}: ${account.balance} (nonce ${account.nextNonce})"
            }, Ink.MUTED),
        )
        val details = pretty.encodeToString(JsonElement.serializer(), controller.detailValue())
        val body: List<UiLine>
        if (controller.helpOpen) {
            val help = listOf(
                "F2 — выбор раздела; стрелки и Enter — открыть.", "Tab / Shift+Tab — переход по полям и действиям.",
                "Стрелки — выбор кошелька, количества листьев или записи.", "Enter — выполнить выделенное действие / открыть подробности.",
                "PgUp / PgDn — прокрутка текущей панели.", "Esc — закрыть подробности / меню / справку; затем выйти.",
                "F1 — показать или закрыть справку.", "В текстовых полях: Ctrl+A, Backspace, Delete, Home/End, стрелки.",
                "Полные ключи, хэши и результаты доступны в подробностях и журнале.",
                "Блок: сначала создайте pending, затем сформируйте кандидатов и голосуйте.",
                "NO_VOTE у двух узлов даёт NO_QUORUM без фиксации.",
                "Эксперименты выполняются в отдельной сессии.",
                "Каталог материалов: ${controller.session.reports.directory.toAbsolutePath()}",
                "Сохранение: ${(controller.session.lastWrite as? WriteResult.Failed)?.reason ?: "успешно"}",
            ).flatMap { wrap(it, workWidth - 6).map(::line) + line("") }
            controller.scrollMax = (help.size - bodyHeight + 2).coerceAtLeast(0)
            controller.scroll = controller.scroll.coerceIn(0, controller.scrollMax)
            body = panel("Справка", help, workWidth, bodyHeight, controller.scroll)
        } else if (controller.menuOpen && navWidth == 0) {
            body = panel("Разделы — Enter открыть", menu(controller, workWidth - 6), workWidth, bodyHeight)
        } else {
            val mainWidth = if (split) (workWidth - panelGap) / 2 else workWidth
            val content = content(controller, mainWidth - 6)
            val detailWidth = if (split) workWidth - mainWidth - panelGap else workWidth
            val detailLines = details.lines().flatMap { wrap(it, detailWidth - 6).map(::line) }
            val bodyLimit = (content.size - bodyHeight + 2).coerceAtLeast(0)
            controller.bodyOffset = controller.bodyOffset.coerceIn(0, bodyLimit)
            if (controller.revealFocus && !controller.detailsOpen) {
                val selected = content.indexOfFirst { it.selected }
                if (selected >= 0) {
                    if (selected < controller.bodyOffset) controller.bodyOffset = selected
                    if (selected >= controller.bodyOffset + bodyHeight - 2) controller.bodyOffset = selected - bodyHeight + 3
                }
            }
            controller.scrollMax = if (controller.detailsOpen) (detailLines.size - bodyHeight + 2).coerceAtLeast(0) else bodyLimit
            controller.scroll = controller.scroll.coerceIn(0, controller.scrollMax)
            body = when {
                split -> combine(
                    panel(controller.section.title, content, mainWidth, bodyHeight, controller.bodyOffset),
                    panel("Подробности${if (controller.detailsOpen) " [PgUp/PgDn]" else " [Enter]"}", detailLines, detailWidth, bodyHeight, controller.detailOffset),
                )
                controller.detailsOpen -> panel("${controller.section.title}: подробности", detailLines, workWidth, bodyHeight, controller.scroll)
                else -> panel(controller.section.title, content, workWidth, bodyHeight, controller.bodyOffset)
            }
        }
        controller.revealFocus = false
        val working = if (navWidth == 0) body else combine(panel("Разделы [F2]", menu(controller, navWidth - 6), navWidth, bodyHeight), body)
        val writeError = controller.session.lastWrite as? WriteResult.Failed
        val status = writeError?.let { "ОШИБКА ЭКСПОРТА: ${it.reason}; результаты в памяти. Журнал → экспорт." } ?: controller.status
        val statusInk = when { status.startsWith("ОШИБКА") || status.startsWith("ОТКАЗ") -> Ink.ERROR; status.startsWith("УСПЕХ") -> Ink.SUCCESS; else -> Ink.WAITING }
        val footer = listOf(
            line("─".repeat(totalWidth), Ink.BORDER), line(status, statusInk),
            line("Tab/Shift+Tab  Стрелки  Enter  PgUp/PgDn  F1 помощь  F2 разделы  Esc выход", Ink.MUTED),
            line("${controller.section.title}  |  шагов ${controller.session.reports.snapshot().events.size}  |  прокрутка ${controller.scroll}/${controller.scrollMax}", Ink.MUTED),
        )
        return UiFrame((header + working + footer).map { padOuter(it, totalWidth) }, navWidth, split)
    }

    private fun content(controller: TuiController, width: Int): List<UiLine> = buildList {
        val blockchain = controller.session.blockchain
        val snapshot = blockchain.snapshot()
        fun text(value: String, ink: Ink = Ink.TEXT) { addAll(wrap(value, width).map { line(it, ink) }) }
        fun action(label: String, index: Int, enabled: Boolean = true) {
            add(line("${if (controller.focus == index) ">" else " "} [${if (enabled) label else "$label — недоступно"}]", if (enabled) Ink.ACCENT else Ink.MUTED, selected = controller.focus == index))
        }
        fun field(label: String, value: String, index: Int) {
            val selected = controller.focus == index
            add(line("${if (selected) ">" else " "} $label: $value", if (selected) Ink.ACCENT else Ink.TEXT, selected = selected))
        }
        fun input(label: String, input: TextInput, index: Int) {
            val prefix = "${if (controller.focus == index) ">" else " "} $label: "
            val room = (width - prefix.length).coerceAtLeast(1)
            val value = visible(input.value.substring(0, input.cursor)) + if (controller.focus == index) "|" else ""
            val suffix = visible(input.value.substring(input.cursor))
            val start = (value.length - room + 1).coerceAtLeast(0)
            add(line(prefix + (value + suffix).drop(start).take(room), if (controller.focus == index) Ink.ACCENT else Ink.TEXT, selected = controller.focus == index))
        }
        fun item(value: String, index: Int, focus: Int) {
            val selected = controller.selected == index
            add(line("${if (selected) ">" else " "} $value", if (selected) Ink.ACCENT else Ink.TEXT, selected = selected && controller.focus == focus))
        }
        when (controller.section) {
            Section.WALLETS -> {
                text("Имя    Баланс   nonce  Адрес", Ink.MUTED)
                blockchain.wallets.forEachIndexed { index, wallet ->
                    val account = snapshot.ledger.accounts.getValue(wallet.address)
                    item("${wallet.name.padEnd(5)} ${account.balance.toString().padEnd(8)} ${account.nextNonce.toString().padEnd(5)} ${wallet.address.hex.take(16)}…", index, 0)
                }
                text(""); text("Enter — полный адрес, raw-ключ и состояние.", Ink.MUTED)
            }
            Section.SIGNATURES -> {
                input("Сообщение", controller.originalMessage, 0)
                input("Изменённое", controller.changedMessage, 1)
                action("Подписать A и проверить A / изменение / B", 2)
                action("Полные результаты", 3)
                text("")
                val result = controller.details[Section.SIGNATURES] as? JsonObject
                if (result == null) text("Введите сообщения, затем выберите действие.", Ink.MUTED) else {
                    text("Исходное: ${result["original_valid"]}; изменение: ${result["changed_valid"]}; ключ B: ${result["other_key_valid"]}")
                    text("Ed25519 подписывает исходные UTF-8 байты.", Ink.MUTED)
                }
            }
            Section.TRANSFERS -> {
                field("Отправитель ←/→", blockchain.wallets[controller.sender].name, 0)
                field("Получатель ←/→", blockchain.wallets[controller.recipient].name, 1)
                input("Сумма", controller.amount, 2)
                action("Подписать и разослать N0/N1/N2", 3)
                action("Pending — Enter подробности", 4, snapshot.pending.isNotEmpty())
                text(""); text("Подтверждённый баланс меняется после блока.", Ink.MUTED)
                if (snapshot.pending.isEmpty()) text("Очередь пуста", Ink.MUTED)
                snapshot.pending.forEachIndexed { index, tx ->
                    val from = blockchain.wallets.find { it.address == tx.body.sender }?.name
                    val to = blockchain.wallets.find { it.address == tx.body.recipient }?.name
                    item("$from → $to  ${tx.body.amount}  nonce ${tx.body.nonce}  ${tx.txId.hex.take(14)}…", index, 4)
                }
            }
            Section.MERKLE -> {
                field("Число листьев ←/→", controller.leafCount.toString(), 0)
                action("Рассчитать дерево", 1, controller.leafCount <= snapshot.pending.size)
                action("Полные уровни", 2)
                text("")
                val tree = (controller.details[Section.MERKLE] as? JsonObject)?.get("tree") as? JsonObject
                if (tree == null) text("Для дерева нужны 1–3 записи в pending.", Ink.MUTED) else {
                    text("Корень: ${tree["root"]?.jsonPrimitive?.content}")
                    tree["levels"]?.jsonArray?.forEach { value ->
                        val level = value.jsonObject
                        text("Уровень ${level["level"]}; дублирование: ${level["duplicated_last"]}", Ink.MUTED)
                        level["hashes"]?.jsonArray?.forEach { text(it.jsonPrimitive.content) }
                        text("")
                    }
                }
            }
            Section.BLOCKS -> {
                action("Сформировать кандидатов", 0, snapshot.pending.isNotEmpty())
                action("Голосовать и принять", 1, controller.session.candidates.isNotEmpty())
                action("Раунд с двумя NO_VOTE", 2, controller.session.candidates.isNotEmpty())
                action("Проверить chain", 3)
                action("Кандидаты — Enter подробности", 4, controller.session.candidates.isNotEmpty())
                text("")
                controller.session.candidates.forEachIndexed { index, block -> item("B${index + 1} ${block.header.producer} ${block.blockHash.hex.take(18)}…", index, 4) }
                val result = controller.details[Section.BLOCKS] as? JsonObject
                result?.get("votes")?.jsonArray?.forEach { vote -> text("${vote.jsonObject["node_id"]?.jsonPrimitive?.content}: ${vote.jsonObject["vote"]?.jsonPrimitive?.content}") }
                if (controller.session.candidates.isEmpty()) text("Для B1/B2/B3 создайте три перевода.", Ink.MUTED)
            }
            Section.EXPERIMENTS -> {
                action("Запустить в новой сессии", 0)
                action("Результаты — Enter подробности", 1, controller.experiments.isNotEmpty())
                text("")
                if (controller.experiments.isEmpty()) text("13 обязательных и контрольных проверок.", Ink.MUTED)
                controller.experiments.forEachIndexed { index, result -> item("${if (result.passed) "PASS" else "FAIL"} ${result.name}", index, 1) }
                controller.experimentDirectory?.let { text(""); text("Материалы: $it", Ink.MUTED) }
            }
            Section.JOURNAL -> {
                input("Каталог экспорта", controller.exportRoot, 1)
                action("Повторить полный экспорт", 2)
                text("")
                text("Журнал — Tab, стрелки, Enter для подробностей", Ink.MUTED)
                controller.session.reports.snapshot().events.forEachIndexed { index, event -> item("${event.step}. ${event.operation}", index, 0) }
            }
        }
    }.map { fit(it, width) }

    private fun menu(controller: TuiController, width: Int): List<UiLine> = Section.entries.mapIndexed { index, section ->
        line("${if (index == if (controller.menuOpen) controller.menuSelection else controller.section.ordinal) ">" else " "} ${section.title}", if (section == controller.section) Ink.ACCENT else Ink.TEXT)
    }.map { fit(it, width) }

    private fun panel(title: String, content: List<UiLine>, width: Int, height: Int, offset: Int = 0): List<UiLine> {
        val inner = width - 2 - innerPadding * 2
        val top = "─ ${visible(title)} ".take(width - 2).padEnd(width - 2, '─')
        return listOf(line("┌$top┐", Ink.BORDER, bold = true)) + (0 until height - 2).map { index ->
            val row = fit(content.getOrNull(offset + index) ?: line(""), inner)
            UiLine(listOf(UiSpan("│", Ink.BORDER), UiSpan(" ".repeat(innerPadding), surface = true)) + row.spans.map { it.copy(surface = true) } + listOf(UiSpan(" ".repeat(innerPadding), surface = true), UiSpan("│", Ink.BORDER)))
        } + line("└${"─".repeat(width - 2)}┘", Ink.BORDER)
    }

    private fun combine(left: List<UiLine>, right: List<UiLine>): List<UiLine> = left.zip(right).map { (a, b) -> UiLine(a.spans + UiSpan(" ".repeat(panelGap)) + b.spans) }
    private fun padOuter(value: UiLine, width: Int): UiLine = UiLine(listOf(UiSpan(" ")) + fit(value, width).spans + UiSpan(" "))
    private fun fit(value: UiLine, width: Int): UiLine {
        var remaining = width.coerceAtLeast(0)
        val spans = value.spans.mapNotNull { span ->
            if (remaining == 0) null else span.copy(text = visible(span.text).take(remaining)).also { remaining -= it.text.length }
        }
        return UiLine(spans + if (remaining > 0) listOf(UiSpan(" ".repeat(remaining))) else emptyList(), value.selected)
    }

    private fun line(text: String, ink: Ink = Ink.TEXT, bold: Boolean = false, selected: Boolean = false): UiLine = UiLine(listOf(UiSpan(text, ink, bold = bold)), selected)
    private fun wrap(text: String, width: Int): List<String> = visible(text).chunked(width.coerceAtLeast(1)).ifEmpty { listOf("") }
    private fun visible(text: String): String = buildString {
        text.codePoints().forEach { point ->
            when {
                point in 32..126 || point in 0x400..0x4ff || point in 0x2500..0x257f || point in listOf(0x2190, 0x2192, 0x2026, 0x2014) -> appendCodePoint(point)
                else -> append("\\u{${point.toString(16)}}")
            }
        }
    }
}
