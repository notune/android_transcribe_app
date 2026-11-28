# Database PostgreSQL — подключаемый профиль AEK

**Триггер:** обнаружена PostgreSQL или пользователь планирует её применить. Используй для схем, индексов, миграций, SQL, RLS, блокировок, соединений и производительности. Не использовать для SQLite/MySQL/других СУБД без проверки применимости.

**Роли:** Architect, Backend Engineer, DBA/QA, Security для RLS и доступа.

**Первоисточник:** https://github.com/supabase/agent-skills/tree/main/skills/supabase-postgres-best-practices. Просмотренный commit `c9be0e931b7930f7d02126d04774d904c381e7d7`. В upstream `SKILL.md` объявлена версия `1.1.1` и лицензия MIT. Практики применимы к PostgreSQL не только в Supabase.

### Активация

```bash
python3 tools/aek_activate.py plan --target /path/to/project --profile database-postgres
# после согласования:
python3 tools/aek_activate.py apply --target /path/to/project --profile database-postgres --plan-id PLAN_ID
```

Профиль не подключается к базе и не меняет схему. Перед выполнением SQL/migration нужно проверить версию СУБД, резервное копирование, rollback, нагрузку, RLS и права. Внешний исходный скилл не включён в AEK; при глубокой работе прочитай актуальные соответствующие upstream `references`.

[Скилл](skills/postgres-best-practices/SKILL.md) · [Манифест](manifest.json)
