---
name: aek-help
description: Показать команды AEK и соответствие форматов выбранному провайдеру.
argument-hint: "[команда]"
disable-model-invocation: true
---

# /aek_help

Покажи краткую таблицу:
- /aek_greenfield: новый проект; /aek_brownfield: существующий проект.
- /aek_update: обновить окружение кита; /aek_change: изменить приложение.
- /aek_fix: исправить ошибку; /aek_status: состояние задач.
- /aek_verify: проверить; /aek_resume: продолжить.
- /aek_audit: анализ; /aek_doctor: диагностика.
- /aek_rollback: откат обновления; /aek_help: справка.

Для Codex используй переносимые навыки `$aek-greenfield`, `$aek-change` и аналогично: стандарт Agent Skills требует дефисы в имени. В Claude Code, OpenCode и Pi генерируются команды с подчёркиванием. Видимость в T3 Code зависит от провайдера и его сессии. Старые команды без префикса не создаются. Для точных команд терминала открой docs/SHORTCUTS.md, docs/DAILY_WORKFLOW.md и docs/COMPONENTS.md в установленном ките или отдельном проверенном источнике. Не изменяй файлы при запросе справки.
