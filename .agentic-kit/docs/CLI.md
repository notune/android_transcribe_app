# CLI: безопасное подключение и контроль

## Диагностика (без записи)

```bash
python3 tools/aek.py inspect --target /path/to/project
```

Проверяет признаки стеков, инструкции и кандидатов на CLI в PATH, включая Codex, OpenCode, DSH, Qwen Code и Pi. Не читает ключи и не делает сетевых запросов. **Модели, effort, лимиты, авторизация и MCP требуют отдельной проверки.** Используй `harness-discovery-assignment` для диалога и утверждения маршрутов.

## Короткие команды T3 Code и других агентов

```bash
python3 tools/aek_shortcuts.py plan --scope global
# После согласования:
python3 tools/aek_shortcuts.py apply --scope global --plan-id PLAN_ID
python3 tools/aek_shortcuts.py check --scope global
```

Варианты: `--provider claude`, `--provider codex`, `--provider opencode`, `--provider pi` (повторяемые); `--scope project --target /path/to/project` для команд только в выбранном репозитории. `plan` ничего не записывает, `apply` не перезаписывает пользовательские файлы и требует точный `plan_id`. После установки в T3 Code перезапусти агентную сессию и проверь реальную доступность команд.

[Подробное руководство](SHORTCUTS.md).

## Установка

```bash
python3 tools/aek.py install --target /path/to/project --mode new
python3 tools/aek.py install --target /path/to/project --mode new --apply --plan-id PLAN_ID
# Для существующего проекта замените new на existing
```

Первая команда выдаёт dry-run; вторая записывает только новые файлы после проверки конфликтов. При изменённых файлах установка без перезаписи прерывается. Для brownfield не изменяет существующие AGENTS.md, OpenSpec, CI, приложение или зависимости.

### Новый проект целиком

```bash
python3 tools/aek.py new-project --target /path/to/empty-project --title "Название" --provider opencode
python3 tools/aek.py new-project --target /path/to/empty-project --title "Название" --provider opencode --apply --plan-id PLAN_ID
```

Одна операция создаёт каркас разделов (`README.md`, `.gitignore`, `docs/`, `docs/evidence/`, `reports/`), ставит кит с активацией навыков, устанавливает команды провайдера, фиксирует окружение, запускает `doctor` и делает первый коммит. Вывод содержит `checks` и `handoff`: аргументы `t3_project_create` и `t3_thread_launch` для регистрации проекта и открытия потока с full-access. Сам кит T3 не вызывает и остаётся переносимым; регистрация выполняется агентом в интерфейсе. Подробности: [NEW_PROJECT.md](NEW_PROJECT.md).

## Работа над задачей

```bash
python3 tools/aek.py new-task --target /path/to/project --id APP-001 --title "Новый сценарий" --profile standard --ui
python3 tools/aek.py status --target /path/to/project --task APP-001
python3 tools/aek.py record --target /path/to/project --task APP-001 --key environment_reviewed --file /path/to/project/docs/evidence/env.md
python3 tools/aek.py record --target /path/to/project --task APP-001 --key product_brief_approved --file /path/to/project/docs/evidence/brief.md
python3 tools/aek.py advance --target /path/to/project --task APP-001 --to design
```

Ключевые evidence для профиля standard:
- `design`: environment_reviewed, product_brief_approved, browser_capability (если UI)
- `planning`: design_approved (если UI)
- `implementation`: architecture_simplified, security_screened, routes_confirmed, swarm_planned
- `verification`: implementation_proof
- `release`: реально выполненные tests, security_review, visual_qa (для любого UI), runtime_smoke (при --runtime http)
- `done`: release_approved, final_report

Для critical дополнительно independent_review, recovery_plan перед release. У lite меньший объём; для UI сохраняется согласование дизайна, требуется browser_capability и visual_qa. Доказательства записываются как пути и SHA256; изменённые файлы не засчитываются.

## Реальные проверки, Visual QA и блокировка готовности

Для новой задачи `standard`/`critical` или UI обязательно зарегистрируй задачу **до реализации**. Для обычной небольшой `lite` без UI можно обойтись облегчённым процессом. Если менялись серверный API, БД или потоки, при создании задачи укажи `--runtime http`.

```bash
python3 .agentic-kit/tools/aek.py new-task --target . --id TASK-001 --title "Проверка UI" --profile standard --ui --runtime http
# Для browser_capability и visual_qa нужны реальные отчёты по шаблонам:
python3 .agentic-kit/tools/aek.py record --target . --task TASK-001 --key browser_capability --file .agentic-kit/evidence/TASK-001/browser.json
python3 .agentic-kit/tools/aek.py record --target . --task TASK-001 --key tests --run "python3 -m unittest discover -s tests"
python3 .agentic-kit/tools/aek.py record --target . --task TASK-001 --key runtime_smoke --run "python3 .agentic-kit/tools/aek_http_smoke.py --base-url http://127.0.0.1:8000 --endpoint /api/status --requests 30 --concurrency 8"
python3 .agentic-kit/tools/aek.py record --target . --task TASK-001 --key visual_qa --file .agentic-kit/evidence/TASK-001/visual.json
python3 .agentic-kit/tools/aek.py verify --target . --task TASK-001 --required-profile standard
```

Примеры команд не являются автоматическим разрешением на запуск. `record --run` запускает **один** исполняемый файл без shell, с таймаутом 300 секунд по умолчанию. Запускай только заранее просмотренные команды в разрешённой среде; не включай секреты в аргументы или логи. Для разработки с изменённым деревом можно использовать `--allow-dirty --reason "..." `, но такое свидетельство **не** засчитывается при приёмке. Упавшая команда сохраняет отрицательный результат; `--allow-fail` намеренно отсутствует.

`tests` и `runtime_smoke` не принимают `--file`: файл нельзя считать фактом запуска команды. Отчёт `visual_qa` принимается только с реальными снимками и структурированными данными через `--file`. Запуск Playwright Test регистрируй отдельно через `tests --run`, не выдавай успешное выполнение произвольной команды за визуальную приёмку. Для UI `lite` визуальная приёмка также обязательна.

`verify --base <trusted-base-ref>` проверяет дифф с базовой веткой и обнаруживает распространённые UI-файлы без соответствующего флага `--ui`. Автоматическая классификация неполна: ответственность за точную область и обязательные тесты остаётся у проекта. `--required-profile`, `--require-runtime` и `--require-command` закрепляй в защищённом CI. Последний проверяет точное совпадение запущенной команды с утверждённой, а не только нулевой код выхода. Пример: `templates/AEK_VERIFY_CI.yml`. Скрипт проверки HTTP по умолчанию разрешает только loopback и GET/HEAD; внешние адреса требуют специального согласования.

Если визуальная проверка объективно невозможна, регистрируй исключение только с причиной и **реальным** отдельным подтверждением пользователя:

```bash
python3 .agentic-kit/tools/aek.py waive --target . --task TASK-001 --key visual_qa --reason "Нет браузера в разрешённой среде" --approval-file docs/evidence/user-approval.md
# Только после согласования пользовательского риска:
python3 .agentic-kit/tools/aek.py verify --target . --task TASK-001 --allow-waiver
```

Без флага `--allow-waiver` проверка остаётся заблокированной. CI по умолчанию не принимает исключения. Локальный файл подтверждения **не удостоверяет личность** пользователя; для обязательного контроля используйте защищённые правила репозитория.

## Параллельное планирование

```bash
python3 tools/aek.py swarm --plan examples/swarm-plan.json
```

Результат: волны задач с учётом declared depends_on, writes и resources. Планировщик не создаёт worktrees и не запускает исполнителей: это решение Orchestrator после оценки скрытых зависимостей и полномочий.

## Регистрация скиллов и активация AGENTS.md

Из клона AEK после `install --apply`:

```bash
python3 tools/aek_activate.py plan --target /path/to/project
python3 tools/aek_activate.py apply --target /path/to/project --plan-id PLAN_ID
python3 tools/aek_activate.py check --target /path/to/project
```

`plan` не меняет файлов; `apply` требует точного `plan_id` из согласованного плана, не перезаписывает конфликты; `check` проверяет наличие файлов, а не состояние агентного процесса. [Подробности](BOOTSTRAP.md).


## Необязательный пакет навыков Web Development

Здесь «пакет навыков» означает устанавливаемый набор. Профиль риска `lite`, `standard` или `critical` описан в [METHODOLOGY.md](METHODOLOGY.md) и с ним не путается. В командах параметр по-прежнему называется `--profile`.

По умолчанию AEK ставит только базовые скиллы. Если проект содержит веб-интерфейс, после обычной установки и согласования отдельно активируй пакет:

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile web-development
python3 tools/aek_activate.py apply --target /path/to/project --profile web-development --plan-id PLAN_ID
python3 tools/aek_activate.py check --target /path/to/project --profile web-development
```

`plan` ничего не записывает. `apply` копирует только AEK-обёртку в `.agents/skills/aek-modern-web-guidance/` и при необходимости обычные скиллы, не скачивая внешнюю зависимость. `check` проверяет файлы, не использование навыка агентом. Для первого сетевого вызова `modern-web-guidance@1.0.0` требуется отдельное разрешение. См. `profiles/web-development/README.md`.


## Ещё четыре необязательных пакета навыков

После установки AEK в проект можно **отдельно** активировать пакет навыков, если технология действительно используется:

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile qa-playwright
# Только после согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile qa-playwright --plan-id PLAN_ID
python3 tools/aek_activate.py check --target /path/to/project --profile qa-playwright
```

Значения `--profile`: `web-development`, `qa-playwright`, `security-audit`, `database-postgres`, `ai-evaluation`. Указывайте несколько `--profile` для комбинированного проекта. Внешние инструменты, сканеры, браузеры, запросы к моделям и базы данных не устанавливаются автоматически.

Сначала запустите `plan`; установка/обновление самих пакетов навыков в ранее скопированном `.agentic-kit/` требует отдельного безопасного обновления AEK через diff/PR.


## Knowledge Base: необязательная интеграция OntoShip GitMark

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile knowledge-base
# после согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile knowledge-base --plan-id PLAN_ID

# Загрузка зафиксированного GitMark: отдельное разрешение:
python3 tools/aek_gitmark.py plan --target /path/to/project
python3 tools/aek_gitmark.py install --target /path/to/project --allow-network
python3 tools/aek_gitmark.py check --target /path/to/project
```

После ручного согласования исключений (`.gitmark/` в `.gitignore` и `.agentic-kit` в `.gitmarkignore`) индексировать можно из **корня проекта**:

```bash
python3 .agentic-kit/profiles/knowledge-base/vendor/gitmark.py --root . index
python3 .agentic-kit/profiles/knowledge-base/vendor/gitmark.py --root . search "architecture decision" --json
```

Установка не запускает скачанный скрипт автоматически и не меняет Markdown или Git ignore-файлы. См. `profiles/knowledge-base/README.md`.


## Необязательный пакет навыков Codebase Audit

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile codebase-audit
# После согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile codebase-audit --plan-id PLAN_ID
python3 tools/aek_activate.py check --target /path/to/project --profile codebase-audit
```

Профиль копирует инструкции в `.agents/skills/`, но не устанавливает `shadcn/improve`, не создаёт планы и не запускает исполнителей. Аудит и создание заданий требуют отдельного поручения и сохраняют OpenSpec источником согласованных требований.


## Обновление и откат установленного AEK

Используй новую версию AEK из отдельного клона. Требуется существующий `.agentic-kit/install-manifest.json`.

```bash
python3 tools/aek.py upgrade --target /path/to/project --diff
# После согласования и с точным plan_id:
python3 tools/aek.py upgrade --target /path/to/project --apply --plan-id PLAN_ID

# Откат после успешного обновления:
python3 tools/aek.py rollback --target /path/to/project --id BACKUP_ID
python3 tools/aek.py rollback --target /path/to/project --id BACKUP_ID \
  --apply --plan-id PLAN_ID_ОТКАТА
```

`upgrade` сохраняет ранее активированные скиллы, не включает новые необязательные пакеты навыков и не перезаписывает локальные правки. После обновления можно отдельно использовать `aek_activate.py plan/apply/check --profile codebase-audit`.

[Полная инструкция](UPGRADE.md).

## Повседневная работа и компоненты

- `list-tasks --target PATH`: список задач.
- `resume --target PATH --task ID`: чтение состояния и недостающих оснований без изменения задачи.
- `transition --target PATH --task ID --state blocked|failed|rework|cancelled|active --reason TEXT`: изменение состояния. Для доработки обязателен `--to` с предыдущим этапом.
- `doctor --target PATH`: проверка установки и реестра.
- `components register --target PATH --file FILE`: просмотр регистрации внешнего компонента; применение через `--apply --plan-id ID`.
- `update --target PATH [--candidates FILE] [--component ID]`: единый план обновления. Применение теми же параметрами с `--apply --plan-id ID`.
- `recover --target PATH --id RUN_ID`: просмотр восстановления единого обновления; применение через `--apply --plan-id ID`.

[Полный рабочий процесс](DAILY_WORKFLOW.md) · [Схема реестра и кандидатов](COMPONENTS.md).

## Сохранённый контекст и процессы

Новые задачи могут использовать `new-task --process change|fix|update`. Обычные этапы и требования сохраняются; процесс дополнительно связывает шаги, результаты и число попыток. Для каждого этапа: `process start-step`, работа и `record`, `process finish-step --outcome passed`, затем `advance`. Повтор успешных тестов требует `process refresh-step --step verification --reason "CI reran checks"`. Это не повтор реализации и не сброс лимита попыток. Полные примеры: [PROCESSES.md](PROCESSES.md).

```bash
python3 .agentic-kit/tools/aek.py context --target . --task APP-001 --file context.json
python3 .agentic-kit/tools/aek.py resume --target . --task APP-001
python3 .agentic-kit/tools/aek.py events --target . --task APP-001 --after 0 --limit 100
python3 .agentic-kit/tools/aek.py events --target . --after 0 --limit 100
```

`context.json` содержит только изменяемые поля, например:

```json
{"owner":"engineer","branch":"feature/export","worktree":"/work/product-export","scope":"Экспорт отчёта","next_action":"Проверить пустой отчёт","blockers":[]}
```

Доступны также `summary` и `base_commit`. Контекст не подтверждает согласование и не переключает ветку. `status` и `resume` показывают блокеры, ревизию задачи, последнюю попытку проверки и состояние процесса. Чтение ничего не исправляет. `--allow-waiver` у `process`, `status` и `resume` только включает оценку ранее сохранённых допустимых исключений; по умолчанию они не засчитываются.

После ошибки `stale_task_revision` перечитай задачу. Не повторяй сохранение устаревшего объекта: другой агент мог изменить блокер или отменить работу. Уже запущенная проверка может завершиться после отмены; её результат сохраняется отдельно, задача остаётся отменённой. При незавершённой транзакции состояния используй `state-recover --target .`, затем заново прочитай задачу. Это отличается от `recover --id RUN_ID`, который восстанавливает обновление компонентов.

## Снимок окружения

```bash
python3 .agentic-kit/tools/aek.py environment plan --target .
python3 .agentic-kit/tools/aek.py environment apply --target . --plan-id PLAN_ID
python3 .agentic-kit/tools/aek.py environment check --target .
```

Снимок не устанавливает версии и не подтверждает загрузку навыка агентом. После согласованного изменения окружения просмотри новый план и явно обнови снимок. Подробности в [COMPONENTS.md](COMPONENTS.md).

`record --run` ограничивает сохранённый вывод 1 МиБ и отмечает усечение; лишние данные считываются без накопления в памяти. Чувствительные переменные окружения фильтруются по имени. Для необходимого доступа передай имя, не значение: `--allow-env SERVICE_API_TOKEN`. Известные значения таких секретов маскируются в выводе даже при разрешённой передаче. Это не песочница и не универсальная очистка секретов из файлов или аргументов.

## Независимые проверки

Назначения, импорт launch/result, миграция и граница доверия описаны в [INDEPENDENT_REVIEWS.md](INDEPENDENT_REVIEWS.md). Для новых standard/critical Markdown-рецензия не заменяет структурированный результат.

```bash
python3 .agentic-kit/tools/aek.py execution route --target . --task TASK-001 --file ROUTE.json
```

`ROUTE.json` фиксирует `providerInstanceId` и `source` ведущей сессии до назначений; `model` и `options` необязательны и сохраняются, если указаны. Политика 2 сверяет с ним провайдеров проверяющих и требует роли по риску: `standard`: один проверяющий, `critical`: два, включая `security`. Провайдер сравнивается без окружающих пробелов. Смена провайдера исполнителя после назначений запрещена.

## Граница выполнения

`new-task --stop-after STAGE` сохраняет включительный предел поручения (по умолчанию verification). Для расширенной границы используй `--user-message-ref REF --user-instruction TEXT`. Команда `boundary --target PROJECT --task ID --stop-after STAGE --user-message-ref REF --user-instruction TEXT` явно меняет предел или мигрирует открытую задачу. Сохраняй действительное сообщение пользователя, не собственный файл одобрения. Подробности и ограничения доверия: [PROCESSES.md](PROCESSES.md).
