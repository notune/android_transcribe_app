# QA Playwright — подключаемый профиль AEK

**Триггер:** продукт имеет браузерные пользовательские сценарии, Playwright Test уже используется либо одобрен для внедрения. Не применять к API-only, нативным приложениям, unit-тестам без браузера или задачам, не требующим E2E.

**Роль:** QA (ответственный), Product Designer (критерии сценариев), Engineer (фикстуры). Сохраняются независимая приёмка, тестовые доказательства и существующий QA workflow AEK.

## Основной браузер и альтернативы

**Основной выбор:** [Microsoft Playwright MCP](https://github.com/microsoft/playwright-mcp), когда **выбранный агент** поддерживает и действительно видит его инструменты. Для диагностики JavaScript, сети и производительности при отдельном разрешении используй [Chrome DevTools MCP](https://github.com/ChromeDevTools/chrome-devtools-mcp). Если MCP не передаётся через T3 Code выбранному агенту, используй [Playwright CLI](https://github.com/microsoft/playwright-cli) с доступным терминалом. Для независимых повторяемых тестов в CI используй **Playwright Test**, не протокол MCP.

Перед установкой проверь официальный источник и точную версию пакета, затем получи согласование. В проектной конфигурации не используй `@latest`; AEK намеренно не закрепляет один неподтверждённый номер версии за все проекты. При обзоре исходников: Playwright MCP `78eca85361efb545a4c0f59b6140aef0721c5412`, Chrome DevTools MCP `8ba2ac945ebaf1adb92208c29f3a30e73a920956`, Playwright CLI `b21724888b8100b05ef8dee3a92f2bb03bf4dcde`. Эти коммиты являются **снимком документации, а не npm-версиями**.

Пример аргументов после выбора и фиксации версии пакета:

```text
@playwright/mcp@<APPROVED_VERSION> --isolated --headless
chrome-devtools-mcp@<APPROVED_VERSION> --isolated --no-usage-statistics --no-performance-crux
```

Запускай каждый MCP-сервер локально и с минимальными полномочиями. Не подключай личные профили, внешние сайты и рабочие учётные записи. Ограничение `--allowed-origins` у Playwright MCP не является полноценной сетевой границей. Используй отдельный контейнер/ограничения ОС для чувствительного кода. Chrome DevTools MCP по необходимости, **не** вместе с полным набором инструментов Playwright MCP во всех сеансах.

## Официальная основа

- Microsoft Playwright Test Agents: https://github.com/microsoft/playwright/blob/main/docs/src/test-agents-js.md
- Подтверждённый upstream commit при обзоре: `bac1f9f67e9cff2a8e7ecbac25d56ec3bb1d15c6`.
- Штатные роли Playwright: `planner` (test plan), `generator` (создание тестов), `healer` (ремонт тестов после запуска). Это **не** замена Product Manager, QA и Security.
- Поддерживаемые примеры генерации определений через `npx playwright init-agents --loop=codex` или `--loop=opencode`. Проверяй текущую версию Playwright, зависимости и поддерживаемые варианты, прежде чем запускать.

## Подтверждение работы браузера

До реализации UI определите в `templates/HARNESS_DISCOVERY.md` статус `not-needed`, `available`, `unavailable` или `forbidden`. Для UI нужен реально проверенный `available` и запись `browser_capability` (см. `templates/BROWSER_CAPABILITY.md`). Если браузер недоступен, требуй отдельного согласования исключения. Нельзя считать незапущенный браузер успешно проверенным.

При визуальной приёмке используй `templates/VISUAL_QA.md`: реальные действия, снимки, консоль, сетевые ошибки, клавиатура и фокус. Регистрируй структурированный отчёт через `aek.py record --key visual_qa --file ...`, а фактически выполненный Playwright Test отдельно как `tests --run`. Произвольная успешная команда не является Visual QA. Невыполненные проверки блокируют `release` и `done` до решения пользователя.

## Проверка поведения интерфейса

Для затронутых сценариев проверь по [критериям AEK](../../docs/DESIGN.md) клавиатуру, доступные названия и роли элементов, возврат фокуса, ошибки и отмену. Проверки в настоящем браузере обязательны там, где поведение зависит от фокуса, положения или наложения окон. При необходимости работы с ИИ-агентами дополнительно проверь отказ в запрещённых действиях и защиту чувствительных данных. Клиентская проверка не заменяет серверную.

## Подключение

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile qa-playwright
# после согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile qa-playwright --plan-id PLAN_ID
python3 tools/aek_activate.py check --target /path/to/project --profile qa-playwright
```

Регистрация **не устанавливает Playwright**, браузеры и пакеты и не генерирует определения внешних агентов. Для `init-agents` нужен отдельный просмотр изменений и согласование. Автоматический healer не имеет права маскировать реальные дефекты приложения или менять критерии приёмки. Не запускать на продуктивных учётных записях без разрешения.

[Скилл](skills/playwright-test-agents/SKILL.md) · [Манифест](manifest.json)
