# Implementation proof: OVI-001

## Проверяемый коммит

- Коммит кода и документов: `7bf5a24498120458e1e10ca81e579e9924370277`
- HEAD при проверке: этот коммит плюс коммиты, меняющие только
  `.agentic-kit/evidence/OVI-001/`; код и документы не меняются
- База: `7abe07a2125733d008a21eb5a039e8204d3c6572` (v0.2.3)
- Ветка: `feature/handy-post-processing`

## Что изменено

### Распознавание

- `app/build.gradle.kts`: сборка `standard` скачивает
  `gigaam-v3-e2e-rnnt-Q6_K.gguf` (227 953 952 байта) и проверяет SHA-256
  `1ef0f771d9ab0b0675c45ce67a3e5b79ac7497fe35ebb08a51704c4ee423edce`.
  Прежний Parakeet Q4_K_M удаляется из `model_assets`.
- `ModelStore.java`: рекомендуемая модель, размер и URL указывают на GigaAM.
- Строки в семи локалях: название, описание, размер загрузки 228 МБ.
- Новый рантайм не добавлялся: `transcribe.cpp` 0.4.0 уже содержит
  архитектуру `gigaam`.

### Постобработка

- `PostProcessingLogic.java`, `OpenAiCompatibleClient.java`,
  `PostProcessor.java`, `SecretStore.java`, `EditorTarget.java`.
- `MainActivity.java`: карточка настроек, проверка связи, предупреждение
  о `http://`.
- `RustInputMethodService.java`, `RecognizeActivity.java`,
  `VoiceRecognitionService.java`: очистка во всех трёх путях, отмена,
  проверка сессии и поля, откат на исходный текст.
- `AndroidManifest.xml`: разрешение `INTERNET`, `usesCleartextTraffic`.
- `activity_main.xml`, переводы: элементы карточки и сообщения.

### Тесты и документы

- `PostProcessingLogicTest.java`, `OpenAiCompatibleClientTest.java`.
- `tests/run_post_processing_cases.py`, `tests/fixtures/post-processing-cases.json`.
- `docs/post-processing-report.md`, `docs/post-processing-phone-check.md`,
  `docs/handy-post-processing.md`, `docs/architecture.md`,
  `docs/architecture.html`, документы `docs/aek/*`.

### Исправления после проверки qa-1

- `SecretStore`: IV формирует Keystore, ошибка сохранения возвращается
  вызывающему коду.
- `EditorTarget` и `RustInputMethodService`: при отсутствии стабильного id
  поля требуется неизменный сеанс ввода; `View.NO_ID` не считается id.
- `RustInputMethodService.onWindowHidden()`: `stop_on_hide` отменяет
  диктовку и до прихода расшифровки.
- `OpenAiCompatibleClient`: отмена до отправки тела не открывает поток;
  добавлен тест на loopback.
- `RecognizeActivity`: закрытие и `onStop` отменяют диктовку до сетевой
  очистки.
- `VoiceRecognitionService`: проверка колбэка и запуск задачи выполняются
  в главном потоке.
- `RustInputMethodService` и native `voice_session`: номер диктовки
  возвращается с расшифровкой и с сигналом «текста не будет», поэтому
  поздний результат отменённой записи отбрасывается до изменения состояния
  новой. Отказ не сбрасывает состояние идущей записи.
- `app/proguard-rules.pro`: новые JNI-колбэки сохранены от удаления R8;
  проверено сборкой `:app:minifyFdroidReleaseWithR8` и просмотром
  `usage.txt`.

## Фактические результаты на этом дереве

| Проверка | Результат |
|---|---|
| `:app:assembleStandardDebug` | BUILD SUCCESSFUL, APK 231 747 765 байт |
| SHA-256 APK | `082b23b604ea5be6c762cd46d9898599531e574df3908847ab4fa542f8caf062` |
| Содержимое APK | `assets/builtin-model/gigaam-v3-e2e-rnnt-Q6_K.gguf`, 227 953 952 байта |
| `:app:testFdroidDebugUnitTest` | 24 теста, 0 падений, 0 ошибок, 0 пропусков |
| `:app:lintStandardDebug` | 7 ошибок, все в файлах, которые задача не меняла |
| `:app:minifyFdroidReleaseWithR8` | BUILD SUCCESSFUL; новые JNI-колбэки есть в dex и отсутствуют в `usage.txt` |
| GigaAM на хосте | русская фраза распознана с пунктуацией, регистром и числом 16:30 |

Подробности и точные команды: `docs/post-processing-report.md`,
`.agentic-kit/evidence/OVI-001/model-smoke.log`.

## Что не доказано

- Работа на реальном телефоне: микрофон, шум, задержка, память, батарея.
- Качество пользовательской модели очистки на сервере пользователя.
- Поведение в чужих приложениях при смене поля.
