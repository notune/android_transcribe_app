# Security review OVI-001

- date_utc: 2026-11-25T23:45:38Z
- commit: `a887cb96977a0360e888db3a486d53b78e28caec`

## Поиск секретов

```
$ git grep -nIE "sk-[A-Za-z0-9]{8,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA|OPENSSH|EC) PRIVATE KEY-----"
совпадений нет
```

Ключей, токенов и приватных ключей в отслеживаемых файлах нет.

## Личные адреса и пути

```
$ git grep -nIE "/Users/[a-z]|84\.252|sslip\.io"
```

Найдены только локальные пути сборки в служебных документах AEK
(`.agentic-kit/ADOPTION.md`, `.agentic-kit/docs/diagnostics/*`) и в
`docs/aek/environment.md`. Адресов серверов пользователя, ключей и личных
диктовок нет. Эти пути не являются секретом и не дают доступа.

## Разрешения и сеть

```
app/src/main/AndroidManifest.xml:7:  <uses-permission android:name="android.permission.INTERNET" />
app/src/main/AndroidManifest.xml:35: android:usesCleartextTraffic="true"
```

- `INTERNET` нужен только для очистки текста.
- `usesCleartextTraffic` принят как риск для локальных `http://` серверов,
  записан в `docs/aek/security-screen.md`.
- Переадресация в клиенте отключена.
- Ключ хранится в Android Keystore, AES/GCM 256.
- Новых экспортируемых компонентов нет.

## Зависимости

```
implementation("com.google.android.material:material:1.12.0")
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20240303")
```

Новых runtime-зависимостей нет. Тестовые зависимости в APK не попадают.

## Ограничения

- SAST и SCA отдельными инструментами не запускались.
- Поведение на реальном устройстве не проверялось.
- Качество ответа пользовательской модели не проверялось.
