# Задание независимому проверяющему: OVI-001

## Назначение

- ID назначения: `qa-1`
- Роль: `review`
- Профиль риска: `standard`
- Проверяемый SHA: `ee9e008f78e360e3e030bc3027ef0665edf59365`
- База сравнения: `7abe07a2125733d008a21eb5a039e8204d3c6572` (v0.2.3)
- Задача: `OVI-001`, `docs/aek/product-brief.md`

## Круг 2

Предыдущий круг на `f012c1a` вернул `changes_requested` с четырьмя
важными находками. Они исправлены в `ee9e008`. Проверь исправления и не
повторяй прежние находки, если они закрыты.

## Согласованный объём

- Необязательная очистка распознанного текста через OpenAI-совместимый
  сервер. Аудио остаётся на телефоне, уходит только текст.
- Встроенная модель распознавания GigaAM v3 e2e RNNT Q6_K вместо Parakeet
  в сборке `standard`.
- Визуальный дизайн в задачу не входит. Макеты `docs/design/**` не
  проверять и не комментировать.
- Публикация и выпуск не входят.
- Проверка на реальном телефоне невозможна: в среде нет устройства и
  эмулятора. Это согласованное исключение, а не готовность.

## Область проверки

Входят:

- `app/src/main/java/dev/notune/transcribe/PostProcessingLogic.java`
- `app/src/main/java/dev/notune/transcribe/OpenAiCompatibleClient.java`
- `app/src/main/java/dev/notune/transcribe/PostProcessor.java`
- `app/src/main/java/dev/notune/transcribe/SecretStore.java`
- `app/src/main/java/dev/notune/transcribe/EditorTarget.java`
- `app/src/main/java/dev/notune/transcribe/RustInputMethodService.java`
- `app/src/main/java/dev/notune/transcribe/RecognizeActivity.java`
- `app/src/main/java/dev/notune/transcribe/VoiceRecognitionService.java`
- `app/src/main/java/dev/notune/transcribe/MainActivity.java`
- `app/src/main/java/dev/notune/transcribe/ModelStore.java`
- `app/src/main/java/dev/notune/transcribe/ModelsActivity.java`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values*/strings.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/build.gradle.kts`
- `app/src/test/java/dev/notune/transcribe/*`
- `tests/**`, `docs/**`, `README.md`

Не входят: `app/build/**`, `.gradle/**`, `target/**`, `docs/design/**`,
сгенерированные файлы.

Обязательные проверки:

- `bash ./gradlew :app:testFdroidDebugUnitTest`
- `bash ./gradlew :app:testFdroidDebugUnitTest --tests dev.notune.transcribe.OpenAiCompatibleClientTest`
- `bash ./gradlew :app:lintStandardDebug` (ожидаются 7 старых ошибок)

Для сборки нужны `JAVA_HOME` (JDK 17) и `ANDROID_NDK_HOME`; они уже заданы
в окружении ведущей сессии. Сеть не нужна, тесты идут на loopback.

## Порядок проверки

1. Соответствие объёму: GigaAM вместо Parakeet, очистка во всех трёх путях,
   отсутствие лишних функций и лишних зависимостей.
2. Качество и безопасность: отмена, смена поля, откат на исходный текст,
   хранение ключа, переадресация, ограничение ответа, журналирование.
3. Документы: отчёт не выдаёт непроверенное за проверенное.

## Что считается провалом

- Аудио или путь к аудио попадает в сетевой запрос.
- Ключ или распознанный текст попадает в журнал.
- Ошибка или текст ошибки вставляется в поле вместо исходной расшифровки.
- Отмена не разрывает соединение или результат вставляется после отмены.
- Результат вставляется в другое поле или после новой диктовки.
- Ключ хранится в открытом виде.
- Переадресация разрешена.
- Новые runtime-зависимости без обоснования.
- Отчёт утверждает проверку на телефоне, которой не было.

Не комментировать: стиль, форматирование, вкусовые предпочтения, старые
lint-ошибки, макеты и визуальный дизайн.

`nit`: не больше трёх.

Известные исключения: Visual QA без устройства, `usesCleartextTraffic`
для локальных серверов, GigaAM понимает только русский.

## Ответ

Верни только JSON без Markdown-обёртки, `report_schema: 2`:

```json
{"assignment_id":"qa-1","reviewed_sha":"ee9e008f78e360e3e030bc3027ef0665edf59365",
 "verdict":"approved|changes_requested|blocked",
 "summary":{"findings_total":0,"by_severity":{"important":0,"nit":0,"pre-existing":0}},
 "findings":[],"commands":[]}
```

Не подстраивай вердикт под ожидания автора.
