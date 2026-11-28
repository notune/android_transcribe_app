# Security Audit — подключаемый профиль AEK

**Триггер:** проект содержит значимую поверхность атаки, зависимые пакеты, новые права/системные интеграции или запрошен аудит безопасности. Дополняет встроенные правила Secure by Design и Security Gate, **не заменяет** их.

**Роли:** Security Engineer (ответственный), Architect (исправление системных причин), QA (регрессии).

## Проверенные внешние источники

Trail of Bits, https://github.com/trailofbits/skills, просмотренный commit `e4dd0bb5b15535f7c5a0834187dffba6ac37e4ff`:

- `plugins/static-analysis/skills/semgrep/SKILL.md` — статический анализ с отдельным согласованием плана сканирования и `--metrics=off`.
- `plugins/supply-chain-risk-auditor/skills/supply-chain-risk-auditor/SKILL.md` — риски пакетов/поддержки/зависимостей; учти ограничения на анализ отдельных lock-файлов.
- `plugins/sharp-edges/skills/sharp-edges/SKILL.md` — небезопасные интерфейсы и риск ошибочного использования API.

Реальные сканеры, скрипты и правила не входят в AEK и не запускаются автоматически. Оригинальные skill-инструкции содержат команды, зависящие от собственного дерева Trail of Bits; **не копируй из них команды без установки и аудита исходного пакета**.

## Активация

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile security-audit
# после согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile security-audit --plan-id PLAN_ID
```

До любого сканирования согласуй его объём, данные, сторонние rulesets и сетевые запросы. Не отправляй конфиденциальный исходный код в внешние сервисы без разрешения. Изолируй найденные риски от недоверенного содержимого репозитория. Вывод сканера не гарантирует отсутствие уязвимостей.

[Скилл](skills/security-audit/SKILL.md) · [Манифест](manifest.json)
