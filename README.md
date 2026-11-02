# opencode for Android

[![Android CI](https://github.com/s1lex312/opencode-android/actions/workflows/android.yml/badge.svg)](https://github.com/s1lex312/opencode-android/actions/workflows/android.yml)

Нативный Android-клиент для [opencode](https://opencode.ai) — терминального coding-агента.
Приложение подключается к серверу `opencode serve` по HTTP/HTTPS, показывает список сессий,
ведёт чат с потоковым выводом (SSE), рисует карточки вызовов инструментов и unified diff
изменённых файлов. Опционально умеет поднимать локальный сервер внутри proot (экспериментально).

<p align="center">
  <b>Kotlin · Jetpack Compose · OkHttp · Kotlinx Serialization · DataStore · Keystore</b><br/>
  minSdk 26 · targetSdk 34 · приоритетная архитектура: arm64-v8a
</p>

---

## Что уже работает

| Возможность | Статус |
| --- | --- |
| Список сессий, создание, удаление, кэш списка офлайн | ✅ |
| Чат с агентом: потоковый текст, reasoning, карточки инструментов | ✅ |
| Обработка ошибок, переподключение SSE с экспоненциальным backoff | ✅ |
| Unified diff сессии с подсветкой добавлений/удалений | ✅ |
| Экран настроек: адрес/порт, Basic/Bearer аутентификация, модель, агент | ✅ |
| Политики разрешений (`ask` / `auto-allow` / `deny`) | ✅ |
| Секреты только в `EncryptedSharedPreferences` + Android Keystore | ✅ |
| Политика «HTTPS или localhost/локальная сеть» (enforced в OkHttp) | ✅ |
| Адаптивный touch-friendly Compose UI (телефон/планшет/ландшафт) | ✅ |
| Поддержка двух API opencode: 2.x `HttpApi` и legacy 0.x/1.x REST | ✅ |
| Встроенный proot-сервер: foreground service + health-check | ⚠️ каркас (см. [server/README.md](server/README.md)) |
| Unit-тесты парсинга SSE/diff/событий/редьюсера | ✅ |
| CI: сборка debug APK + тесты в GitHub Actions | ✅ |

## Быстрый старт

### 1. Соберите APK

Требования: JDK 17, Android SDK (compileSdk 34, build-tools 34.0.0), интернет для Gradle-зависимостей.

```bash
git clone https://github.com/s1lex312/opencode-android.git
cd opencode-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Установка:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Тесты и полная проверка:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

> `local.properties` не коммитится. Если Android SDK не в стандартном месте, создайте файл:
> `sdk.dir=/path/to/Android/Sdk`.

### 2. Запустите сервер opencode

На машине с вашим проектом (ноутбук, сервер, Termux):

```bash
# без пароля — только для доверенной сети
opencode serve --hostname 0.0.0.0 --port 4096

# с паролем (HTTP Basic, логин всегда "opencode")
OPENCODE_PASSWORD='длинный-секрет' opencode serve --hostname 0.0.0.0 --port 4096
```

Версия opencode: клиент поддерживает и текущий сервер 2.x (`/api/...`), и legacy-поверхность
0.x/1.x (`/session`, `/event`). Версия определяется автоматически при подключении
(`GET /api/info` → `GET /config`).

CORS настраивать не нужно: это нативное приложение, а не браузер.

### 3. Подключите приложение

1. Откройте **Settings → Server connection**.
2. Введите адрес: `http://192.168.1.20:4096` (та же Wi-Fi сеть) или `http://127.0.0.1:4096`,
   если сервер запущен на телефоне.
3. **Authentication**: `Password` + пароль из `OPENCODE_PASSWORD`
   (или `Token` для reverse-proxy с Bearer).
4. **Test** → должно появиться `Reachable: opencode vX.Y.Z (API v2)`.
   Ключи LLM-провайдеров задаются на сервере (`opencode auth login`), приложение их не хранит:
   оно знает только пароль/токен самого сервера.
5. **Save & connect** → вернитесь на список сессий, создайте сессию, пишите агенту.

Публичные адреса — только `https://`. Открытый HTTP разрешён для `localhost`/`127.0.0.1`,
а для локальной сети (192.168/10/172.16-31, `*.local`) — только если включён переключатель
«Allow cleartext HTTP on the local network» (по умолчанию включён; его можно выключить для
строгого режима).

## Безопасность

- Пароль/токен хранятся в `EncryptedSharedPreferences` (AES-256-SIV для ключей, AES-256-GCM
  для значений), мастер-ключ — в Android Keystore. Если Keystore недоступен, приложение
  честно предупреждает и хранит секрет только в памяти (без plaintext-файлов).
- Секреты никогда не попадают в логи: HTTP-логирование пишет только method/path/status/время;
  заголовок `Authorization` и query-параметры вида `auth_token` редактируются (`Redaction`).
- TLS: валидация сертификата по системному хранилищу, пользовательские CA не принимаются.
- `allowBackup=false` + `dataExtractionRules` исключают попадание настроек и кэша в бэкап.
- Политика cleartext проверяется дважды: при сохранении настроек и в сетевом интерсепторе,
  поэтому закэшированный конфиг её не обойдёт.

Подробности и обоснования — в [ARCHITECTURE.md](ARCHITECTURE.md) и [ASSUMPTIONS.md](ASSUMPTIONS.md).

## Структура репозитория

```
app/src/main/java/ai/opencode/android/
  data/
    remote/      OkHttp-транспорт, SSE-клиент и парсер, V2Api, LegacyApi, JSON-хелперы
    events/      нормализованные события + маппер обоих API + редуктор стрима
    model/       доменные модели (сессии, сообщения, части, разрешения)
    repo/        ServerConnectionManager, SessionRepository, ApiResult
    diff/        парсер unified diff
    cache/       файловый кэш списка сессий
  security/      EncryptedSecretStore, NetworkPolicy, Redaction
  settings/      AppSettings + DataStore-репозиторий
  server/        экспериментальный proot-супервизор + foreground service
  ui/            Compose: тема, навигация, экраны sessions/chat/diff/settings
  di/            ручной DI-контейнер
app/src/test/    unit-тесты SSE, diff, маппера событий, редуктора, политики сети
server/          скрипты и инструкция для proot-развёртывания (опционально)
```

## Известные ограничения

- Нет вложений файлов/картинок в промпте и нет терминала (PTY) — см. [TODO.md](TODO.md).
- Пагинация: загружаются последние 100 сессий и 200 сообщений; бесконечной прокрутки нет.
- Встроенный proot-сервер не поставляется с бинарниками (proot/rootfs) — это осознанное
  решение: см. [server/README.md](server/README.md) и [ASSUMPTIONS.md](ASSUMPTIONS.md#11-встроенный-proot-сервер).
- Стриминг использует эвристику (дельты + перезапрос проекции сообщений после хода агента):
  сервер остаётся источником истины, но при очень длинных ответах возможен короткий «прыжок»
  текста в момент перезапроса.
- Приложение не проверялось на реальном устройстве внутри этой рабочей среды; сборка и
  unit-тесты выполняются в CI (см. `.github/workflows/android.yml`).

## Документация

- [ARCHITECTURE.md](ARCHITECTURE.md) — сравнение вариантов архитектуры, слои, потоки данных, API-маппинг.
- [ASSUMPTIONS.md](ASSUMPTIONS.md) — все допущения, включая трактовку «opencode 8.0+».
- [TODO.md](TODO.md) — приоритизированный бэклог.
- [server/README.md](server/README.md) — proot-сервер: сборка rootfs, установка, ограничения.
- [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) — чек-лист ручной проверки на устройстве.

## Проверка на устройстве

CI доказывает только то, что проект компилируется и unit-тесты проходят. Сеть, UI, Keystore и
стриминг проверяются вручную по чек-листу [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md): подключение
(пароль/без пароля/HTTPS/self-signed), список сессий, стриминг, tool-cards, permissions, diff,
переподключение после обрыва, proot-сервер.

## CI

`.github/workflows/android.yml` на каждый push в `main`/`arena/**` и на PR:

1. JDK 17 + предустановленный Android SDK (ubuntu-latest);
2. `./gradlew testDebugUnitTest`;
3. `./gradlew assembleDebug`;
4. артефакты: `opencode-android-debug` (APK ~18 МБ), `build-logs`, `unit-test-reports`;
5. при падении компиляции/тестов ключевые ошибки публикуются как аннотации к check-run.

Последний известный успешный прогон: run `41407289438` — 55 unit-тестов зелёные, debug APK собран.

## Лицензия

MIT — см. [LICENSE](LICENSE). opencode является проектом его авторов (MIT);
этот репозиторий — независимый клиент и не содержит кода сервера.
