# ConsoleBlockchain

Учебный блокчейн для лабораторной работы 2: три кошелька, Ed25519, SHA-256, pending, дерево Меркла и выбор одного блока голосами N0/N1/N2. Сеть и майнинг не используются. Закрытые ключи создаются заново при запуске и остаются внутри компонента подписи.

Реализованы ядро, экспорт материалов и оба неинтерактивных сценария. Нативная упаковка находится на этапе технической проверки Mosaic; полноценный TUI будет добавлен после прохождения этого этапа.

## Запуск сценариев

Готовый нативный дистрибутив должен состоять из одного `ConsoleBlockchain.exe`. Java, Gradle и исходники на машине запуска не требуются. DLL Mosaic входит в ресурсы и извлекается во временный каталог.

```powershell
.\ConsoleBlockchain.exe --demo
.\ConsoleBlockchain.exe --experiments --output-dir D:\lab-results
```

Каждый запуск использует отдельный каталог с UUID. По умолчанию материалы находятся в `%LOCALAPPDATA%\ConsoleBlockchain\sessions`. Оба сценария выполняют обязательные положительные и отрицательные опыты, включая два `NO_VOTE`, подмену суммы после пересчёта хэшей и повреждение копии цепочки. Итоговые балансы A/B/C: **95/90/115**, nonce: **1/1/1**, pending пуст, сумма 300.

Коды завершения: `0` — успешная проверка и экспорт, `1` — ошибка опыта или сохранения, `2` — неправильные аргументы. Справка: `--help`.

## Сборка для разработчика

Нужны Windows x64 и JDK 21. Gradle Wrapper 8.14 включён в репозиторий. Версии зависимостей заданы в `gradle/libs.versions.toml`. Для совместимости с Kotlin-плагином IntelliJ IDEA ветки 252 используются Kotlin 2.2.20, Serialization 1.9.0, Coroutines 1.10.2 и Compose Runtime 1.9.0. После изменения сборочных скриптов выполните Reload All Gradle Projects в IDEA.

```powershell
.\gradlew.bat test verifyComposeClasspath
.\gradlew.bat installDist
.\build\install\console-blockchain\bin\console-blockchain.bat --demo
.\build\install\console-blockchain\bin\console-blockchain.bat --experiments
```

`verifyComposeClasspath` проверяет отсутствие повторяющихся классов Compose и Kotlin metadata новее 2.2 в прикладном и тестовом classpath. Исходники расположены непосредственно в `src/main/kotlin` по пакетам `model`, `crypto`, `ledger`, `consensus`, `report`, `app`.

Для нативной сборки дополнительно нужны **GraalVM для JDK 21**, **Visual Studio Build Tools 2022** с **MSVC x64/x86** и **Windows 11 SDK**. Эти инструменты нужны только разработчику. Установка Build Tools требует подтверждения UAC и регистрирует компоненты Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-build-tools.ps1
$env:GRAALVM_HOME = 'C:\path\to\graalvm-jdk-21'
.\gradlew.bat nativeProbeCompile
.\build\native\nativeProbeCompile\MosaicProbe.exe
# В стенде: введите символ, измените размер окна, затем нажмите Esc.
.\gradlew.bat nativeCompile
powershell -ExecutionPolicy Bypass -File .\tools\verify-native.ps1
```

`tools/build-native.ps1` объединяет тесты, проверку classpath и обе нативные сборки; он также находит локальную GraalVM в `.gradle/native-tools`. `verify-native.ps1` копирует только EXE в отдельный каталог, удаляет Java из окружения дочерних процессов, запускает оба сценария и проверяет материалы. Исходный PATH и JAVA_HOME восстанавливаются после проверки.

Native Image собирается с `--no-fallback`. JNI callbacks, ресурс DLL и инициализация её загрузчика описаны в `src/main/resources/META-INF/native-image/blockchain`. Генерация ключей происходит во время запуска.

## Сохранённые материалы

| Файл | Содержание |
|---|---|
| `metadata.json` | Идентификатор и тип сессии, время, ОС, реальные версии сборочных инструментов и библиотек |
| `events.jsonl` | Полные публичные результаты операций с последовательными номерами шагов |
| `journal.txt` | Читаемый протокол, проверки и причины отказов |
| `summary.md` | Описание опытов, таблицы, уровни Меркла и полные публичные значения |
| `candidates.csv` | Сравнение B1/B2/B3 по полям, проверке и принятию |
| `balances.csv` | Балансы и nonce до и после операций |
| `experiments.csv` | Ожидаемый и фактический результат 13 проверок |

CSV используют UTF-8 BOM и экранирование для Excel. Ошибка записи не отменяет уже принятую операцию: публичные результаты остаются в памяти сессии для повторного экспорта. Просмотр данных не создаёт событий. `tx_id` и `signature` исключены из подписываемого тела; дерево Меркла, напротив, включает полную подписанную транзакцию.

Это локальная учебная модель. Выбор минимального хэша не является защищённым промышленным консенсусом. Подготовка DOCX/PDF-отчёта, восстановление сессии и импорт закрытых ключей не входят в проект.
