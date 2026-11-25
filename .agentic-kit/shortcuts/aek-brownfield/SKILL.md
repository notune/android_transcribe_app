---
name: aek-brownfield
description: Подключить AEK к существующему проекту с сохранением его правил и кода.
argument-hint: "[область обследования]"
disable-model-invocation: true
---

# /aek_brownfield

1. Проверь корень проекта, Git, инструкции, тесты, CI, OpenSpec и установленный AEK. При наличии манифеста предложи `/aek_update`.
2. Получи доверенный https://github.com/kelvmg/agentic-engineering-kit в отдельный каталог вне проекта через существующую авторизацию. Прочитай AGENTS.md, docs/BOOTSTRAP.md и docs/ADOPTION.md, зафиксируй SHA и VERSION.
3. Из отдельного источника выполни `python3 tools/aek.py inspect --target PROJECT`, затем `python3 tools/aek.py install --target PROJECT --mode existing --activate`.
4. Покажи единый план подключения и активации, изменения AGENTS.md, конфликты и plan_id. Примени согласованный план с `--apply --plan-id ID`. Уже выданное разрешение на этот конкретный план не запрашивай повторно.
5. Проверь результат. Обследуй архитектуру и существующие проверки по согласованной области; отдели прежние сбои от новых. Не меняй код продукта и принятые библиотеки только ради внедрения кита.
6. Для следующего изменения используй `/aek_change` и общее состояние задачи из docs/DAILY_WORKFLOW.md.
