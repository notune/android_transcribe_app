# Проверка браузера до проектирования UI

Подготовь JSON-отчёт `browser-capability.json` с `status: available`, только если выбранный агент **фактически** открыл тестовую страницу в изолированном браузере.

```json
{
  "status": "available",
  "tool": "Playwright MCP",
  "version": "ПРОВЕРЕННАЯ_ВЕРСИЯ",
  "verified_at": "2026-11-13T19:00:00+03:00",
  "mode": "isolated-headless",
  "allowed_hosts": ["127.0.0.1", "localhost"]
}
```

При `unavailable` или `forbidden` не подменяй статус `available`. Проблема решается предоставлением браузера или согласованным исключением. Отчёт регистрации:

```bash
python3 .agentic-kit/tools/aek.py record --target . --task TASK-001 --key browser_capability --file path/to/browser-capability.json
```

Изоляция не доказана наличием флага `--isolated`. При критичных данных используй отдельную среду и сетевые ограничения.
