# Каталог скиллов и условия применения

Для быстрого вызова первоначальной установки и обновления AEK в T3 Code, OpenCode, Claude, Codex и Pi см. [команды `/aek_greenfield`, `/aek_brownfield`, `/aek_update`](SHORTCUTS.md). Это отдельные пользовательские ярлыки, они не подменяют роли и этапы методологии.


Скиллы это **процедуры, запускаемые по триггеру**, а не команды, которые нужно механически выполнять на каждом сообщении. Для установки стороннего скилла проверять исходный репозиторий, commit, лицензию, исполняемые скрипты и влияние на доступ к данным. Открытые каталоги сами по себе не гарантируют качество.

| Роль | Процедуры по ситуации | Источник |
|---|---|---|
| PM | product-discovery-interview; brainstorming | AEK; Superpowers |
| BA | product-discovery-interview; OpenSpec propose/clarify | AEK; OpenSpec |
| Designer | design-quality-gate; frontend-design; ui-ux-pro-max; web-design-guidelines | AEK; Anthropic; nextlevelbuilder; Vercel |
| Architect | library-first-assessment; architecture-simplification; OpenSpec plan/design | AEK; OpenSpec |
| Engineer | root-cause-analysis; test-driven-development; systematic-debugging; verification-before-completion | AEK; Superpowers |
| QA | verification-before-completion; design-quality-gate; webapp-testing при веб-UI | AEK; Anthropic |
| Security | security-threat-model; профильные static analysis / supply-chain | AEK; Trail of Bits |
| SRE | release-readiness; workflow-compliance; CI review | AEK; профильные инструменты |
| Orchestrator | **harness-discovery-assignment**; swarm-planning; workflow-compliance | AEK |

## Подключаемый пакет навыков Web Development

Для проектов с браузерным UI можно **отдельно активировать** пакет `profiles/web-development/`. Он добавляет `aek-modern-web-guidance`, который использует рекомендации GoogleChrome Modern Web Guidance по актуальным веб-API, совместимости, доступности, производительности и клиентской безопасности. Здесь «пакет навыков» это устанавливаемый набор; профиль риска описан в [METHODOLOGY.md](METHODOLOGY.md).

Пакет **не входит** в обязательные скиллы каждого проекта и не устанавливает внешний CLI автоматически. Сохраняется разделение: UI/UX Pro Max и Frontend Design для визуальной концепции; Web Design Guidelines для аудита; Modern Web Guidance для современной реализации. Chrome Extensions это отдельный необязательный скилл.

См. [пакет](../profiles/web-development/README.md), [источник](https://github.com/GoogleChrome/modern-web-guidance), [порядок установки](CLI.md).

## Дополнительные пакеты навыков

Кроме `web-development` доступны четыре **необязательных** пакета: `qa-playwright`, `security-audit`, `database-postgres`, `ai-evaluation`. Они включают переносимые инструкции AEK и ссылки на проверенные upstream-проекты, **не устанавливают** внешние сканеры, браузеры, БД, Promptfoo или их зависимости.

Роли и применимость:
- QA → [Playwright Test Agents](../profiles/qa-playwright/README.md), только браузерные E2E.
- Security → [Trail of Bits](../profiles/security-audit/README.md), только по риску и согласованному плану проверки.
- Architect / Backend → [Supabase Postgres Best Practices](../profiles/database-postgres/README.md), только PostgreSQL.
- QA / AI Engineer / Security → [Promptfoo AI Evaluation](../profiles/ai-evaluation/README.md), при LLM/RAG/агентах; red team отдельно согласовывается.

Другие источники, которые не устанавливаются, перечислены в [каталоге для последующего выбора](OPTIONAL_SKILL_CATALOG.md).

## Knowledge Base (OntoShip)

[Необязательный пакет навыков](../profiles/knowledge-base/README.md) с тремя скиллами: `aek-kb-search` для поиска знаний, `aek-kb-curate` для поддержания структуры и `aek-kb-audit` для отчётной проверки документации. Применяется к крупным Markdown-базам и brownfield проектам, не включается в ядро и не навязывает онтологию OpenSpec.

Источником GitMark является зафиксированный файл из [OntoShip](https://github.com/vakovalskii/ontoship), загружаемый только по разрешению с проверкой хеша. `dev-flow` и `/ship` не подключаются.

## Codebase Audit

Необязательный [пакет навыков `codebase-audit`](../profiles/codebase-audit/README.md) использует подход [shadcn/improve](https://github.com/shadcn/improve) и добавляет скиллы `aek-codebase-audit` (проверяемые замечания), `aek-handoff-plan` (самостоятельные задания другим моделям) и `aek-plan-reconcile` (проверка устаревания планов и результатов). Он не запускает чужих агентов и не создаёт вторую систему требований.

Доступность и точные имена внешних скиллов проверяются на зафиксированном commit: не все перечисленные имена гарантированно устанавливаются одинаково во всех харнессах. Дублирующие процессы OpenSpec и Superpowers не запускать дважды.

Источники: https://github.com/Fission-AI/OpenSpec , https://github.com/obra/superpowers , https://github.com/anthropics/skills , https://github.com/nextlevelbuilder/ui-ux-pro-max-skill , https://github.com/vercel-labs/agent-skills , https://github.com/trailofbits/skills , https://agentskills.io/
