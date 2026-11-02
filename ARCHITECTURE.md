# Архитектура opencode для Android

Документ фиксирует выбранную архитектуру, альтернативы, которые были рассмотрены, и то, как
требования «клиент к opencode serve» превратились в конкретные слои кода.

## 1. Контекст

opencode — клиент-серверная система. Ядро написано на TypeScript/Bun: оно владеет сессиями,
историей сообщений, доступом к файлам, запуском инструментов и провайдерами LLM. Клиенты
(TUI, web, desktop) общаются с ядром по HTTP и получают события через Server-Sent Events.

Сервер (`opencode serve`) предоставляет два поколения HTTP-поверхности:

| Поколение | Префикс | Формат событий | Кто использует |
| --- | --- | --- | --- |
| **v2 `HttpApi`** (opencode 2.x) | `/api/session`, `/api/event`, `/api/model`, `/api/info` | `{id, created, type, data:{...}}` | актуальные релизы |
| **legacy REST** (opencode 0.x/1.x) | `/session`, `/event`, `/config/providers` | `{type, properties:{...}}` | старые серверы, часть дистрибутивов |

Мобильному клиенту нужно ровно то, что умеет сервер: список сессий, проекция сообщений,
поток событий, диффы, разрешения и каталог моделей. Поэтому имеет смысл не тащить ядро на
Android, а сделать качественный нативный клиент.

## 2. Сравнение вариантов

### Вариант A — нативный клиент к `opencode serve` (**выбран**)

**Плюсы**

* ядро остаётся там, где оно эффективно (ноутбук/сервер/десктоп), с полным доступом к
  файловой системе, git, LSP и MCP;
* APK маленький, без Node/Bun/proot и без эмуляции syscall-ов;
* стабильный батарейный профиль: сеть только при активном использовании;
* простая модель безопасности: TLS + Basic/Bearer, никакого выполнения чужого кода на телефоне;
* работает поверх Tailscale/WireGuard/reverse-proxy — обычный HTTPS.

**Минусы**

* нужен внешний сервер; «в кармане» приложение бесполезно без сети (кэш сессий частично решает);
* нельзя работать с файлами на самом телефоне, если сервер не на телефоне.

### Вариант B — встроенный proot-сервер (rootfs + Bun + opencode) в foreground service

**Плюсы**

* полностью автономно, работает офлайн, файлы — на телефоне;
* нет root-требований (proot — user-space).

**Минусы**

* нужен arm64 rootfs (~150–300 МБ), proot-бинарь и Bun/Node внутри;
* скорость: proot добавляет overhead на syscall-ы, Bun на Android-ядре — не гарантирован;
* хрупкость: Android убивает процессы, Doze, ограничения фоновых сервисов, linker/glibc;
* лицензионные/размерные вопросы при распространении rootfs в APK;
* для проверки нужен реальный arm64-девайс, чего нет в CI.

### Вариант C — WebView-обёртка над web-UI opencode

**Плюсы**: минимум кода, весь функционал сервера сразу.

**Минусы**: не нативный UX, нет офлайн-кэша, плохая работа с SSE/вложениями/клавиатурой,
невозможно аккуратно хранить секреты (cookies в WebView), нет diff-экрана под палец.

### Вариант D — гибрид A + B (**выбран как компромисс**)

Нативный клиент — основной путь. Встроенный сервер реализован как **опциональный
супервизор**: foreground service поднимает proot-процесс `opencode serve`, следит за health
endpoint и показывает логи. Бинарники не поставляются (осознанно, см. ASSUMPTIONS), поэтому
фича помечена экспериментальной и не влияет на основной сценарий.

**Итог:** основной продукт — вариант A, вариант B доступен как экспериментальный режим
без компромиссов для сборки и UX.

## 3. Слои приложения

```
┌───────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose)                                          │
│  SessionsScreen   ChatScreen   DiffScreen   SettingsScreen     │
│  ViewModels (StateFlow) + навигация NavHost                   │
└───────────────▲───────────────────────────────┬───────────────┘
                │ UiState                       │ команды
┌───────────────┴───────────────────────────────▼───────────────┐
│ Repositories                                                  │
│  ServerConnectionManager  — единственное соединение, SSE,     │
│                             состояние подключения             │
│  SessionRepository        — операции + кэш списка сессий      │
│  SettingsRepository       — DataStore (не секреты)            │
└───────────────▲───────────────────────────────┬───────────────┘
                │ OpencodeEvent                 │ OpencodeApi
┌───────────────┴───────────────────────────────▼───────────────┐
│ Data / protocol                                               │
│  EventMapper (v2|legacy → OpencodeEvent)                      │
│  ChatStreamReducer (дельты/части → список сообщений)          │
│  UnifiedDiffParser (patch → DiffFile/DiffHunk/DiffLine)       │
│  V2Api / LegacyApi → ServerHttp (OkHttp)                      │
│  SseStream (реконнект + backoff) + SseParser                  │
└───────────────▲───────────────────────────────┬───────────────┘
                │                               │
┌───────────────┴───────────────────────────────▼───────────────┐
│ Security / platform                                           │
│  EncryptedSecretStore (Keystore)  NetworkPolicy  Redaction    │
│  LocalServerManager + LocalServerService (опционально)        │
└───────────────────────────────────────────────────────────────┘
```

Принципы:

* **Один сокет на приложение.** `ServerConnectionManager` живёт в `AppContainer` (app-scope)
  и держит единственный SSE-поток, раздавая события через `SharedFlow`. Переходы между
  экранами не пересоздают соединение.
* **ViewModel — единственное место, где смешиваются состояния.** Репозитории возвращают типизированный
  `ApiResult`, а не бросают исключения в UI.
* **Чистая логика отделена от Android.** `SseParser`, `UnifiedDiffParser`, `EventMapper`,
  `ChatStreamReducer`, `NetworkPolicy`, `MarkdownLite.parse`, `Formatters` не зависят от
  `Context` и покрыты JVM-тестами.

## 4. Поток данных

### 4.1 Подключение

1. `SettingsRepository.current()` → `AppSettings` (адрес, режим auth, флаги).
2. `NetworkPolicy.normalize()` проверяет схему/хост/порт (HTTPS или локальные адреса).
3. `ApiFactory` создаёт `ServerHttp` (OkHttp + `AuthInterceptor` + `NetworkPolicyInterceptor`).
4. Определение версии API:
   * `GET /api/info` → 2xx ⇒ `V2Api`;
   * иначе `GET /config` → 2xx ⇒ `LegacyApi`;
   * 401/403 прерывают попытку с понятной ошибкой авторизации.
5. Соединение открывает `api.eventStream()`; `StreamSignal.Opened` переводит состояние в
   `Connected`, `Failure` — в `Reconnecting`/`Failed`.

### 4.2 Список сессий

`SessionRepository.loadSessions()` → `GET /api/session?limit=100&order=desc` (v2) или
`GET /session` + `GET /session/status` (legacy). Результат нормализуется в `ServerSession`
и сохраняется в файловый кэш (`SessionCache`). События `session.created/renamed/deleted`
вызывают тихий refresh с коалесцированием (не чаще раза в секунду).

### 4.3 Чат и стриминг

Загрузка истории:

* v2: `GET /api/session/{id}/message?limit=200&order=asc` → проекции `user`/`assistant`;
  `assistant.content[]` содержит `text` / `reasoning` / `tool`.
* legacy: `GET /session/{id}/message` → `[{info, parts[]}]`, части маппятся тем же кодом,
  что и события (`EventMapper.legacyPart`).

Живой поток:

| v2 событие | Нормализованное | Действие |
| --- | --- | --- |
| `session.text.delta` | `TextDelta` | дописать текст в часть `text-{ordinal}` |
| `session.text.ended` | `TextCompleted` | заменить текст авторитетным значением |
| `session.tool.input.started` | `ToolStarted` | создать карточку инструмента |
| `session.tool.called` | `ToolCalled` | показать input, статус running |
| `session.tool.success/failed` | `ToolSucceeded/ToolFailed` | output/error, финальный статус |
| `session.status`, `session.execution.*` | `Status` | индикатор «working…», retry-сообщения |
| `session.idle` | `Idle` | снять busy, перезапросить историю |
| `permission.asked` | `PermissionAsked` | карточка разрешения / автоответ по политике |
| `session.created/renamed/deleted` | `SessionsChanged` | обновить список сессий |

| legacy событие | Нормализованное |
| --- | --- |
| `message.updated` | `MessageUpserted` |
| `message.part.updated` (+`delta`) | `PartUpserted` |
| `message.part.removed` / `message.removed` | `PartRemoved` / `MessageRemoved` |
| `session.idle`, `session.status` | `Idle`, `Status` |
| `session.error` | `SessionFailed` |
| `permission.updated` / `permission.replied` | `PermissionAsked` / `PermissionResolved` |
| `session.diff` | `DiffChanged` |

`ChatStreamReducer` — чистая функция `(messages, event) -> messages?`: создаёт сообщение
ассистента по id из события, сливает дельты, обновляет карточки инструментов по `callID`,
корректно обрабатывает legacy-части, где текст приходит кумулятивно (`text` растёт, `delta`
дополняет). После `idle`/`execution.*` ViewModel перезапрашивает проекцию сообщений — сервер
остаётся источником истины, а редуктор даёт плавный «хвост» между запросами.

### 4.4 Разрешения

`permission.asked` → `PermissionRequest`. Политика из настроек:

* `ASK` — карточка над полем ввода (Allow / Always / Reject);
* `AUTO_ALLOW` — автоматический `{"decision":"once"}`;
* `DENY` — автоматический `{"decision":"reject"}`.

v2: `POST /api/session/{id}/permission/{requestID}/reply`, legacy:
`POST /session/{id}/permissions/{permissionID}` с `{"response": ...}`.

### 4.5 Diff

`GET /api/session/{id}/diff` (или legacy `GET /session/{id}/diff`) возвращает список
`{file, patch, additions, deletions, status}`. `UnifiedDiffParser` разбирает `patch`:
git-преамбулы, несколько файлов, несколько хунков, `\ No newline at end of file`,
бинарные файлы, CRLF. Экран показывает файл-карточку, статус, счётчики и построчный diff с
номерами строк и горизонтальным скроллом для длинных строк.

## 5. Обработка ошибок и переподключение

* Сетевые ошибки маппятся в человекочитаемые сообщения (`NetworkErrors`), тела ответов
  обрезаются и редактируются (`Redaction.preview`).
* `SseStream` переподключается с экспоненциальным backoff (1 → 2 → 4 → … → 30 с) + джиттер.
  При 401/403/404 поток останавливается: ретраить бессмысленно, нужно менять настройки.
* «Мягкие» обрывы (EOF, таймаут) переводят UI в `Reconnecting(attempt, reason)`, при
  восстановлении — обратно в `Connected` и тихое обновление данных.
* Ошибка авторизации отображается и на экране сессий, и в чате с подсказкой «проверьте пароль».
* Кнопка Retry есть у всех баннеров ошибок; `SessionsViewModel` при неудачном refresh
  не затирает уже показанный список.

## 6. Хранение данных

| Что | Где | Почему |
| --- | --- | --- |
| Адрес, auth-режим, модель, агент, политики, настройки локального сервера | DataStore Preferences | транзакционно, Flow из коробки |
| Пароль/токен | `EncryptedSharedPreferences` + Keystore | требование «только шифрованное хранилище» |
| Кэш списка сессий | JSON-файл в `cacheDir` | переживает перезапуск, не является вторым источником истины |
| Сообщения | только в памяти + перезапрос с сервера | избегаем рассинхронизации и роста БД |

Room сознательно не используется: клиент — тонкое представление удалённого состояния, и одна
небольшая JSON-модель проще, чем схема, миграции и KSP без выигрыша. Это зафиксировано в
ASSUMPTIONS; при появлении офлайн-режима с полноценной историей Room станет оправдан.

## 7. Безопасность

* **Секреты.** `EncryptedSecretStore` использует `MasterKey` (AES-256-GCM) и
  `EncryptedSharedPreferences` (AES-256-SIV для ключей, AES-256-GCM для значений). При
  недоступности Keystore — память, явное предупреждение в UI, никакого plaintext-фолбэка.
* **Сеть.** `NetworkPolicy` разрешает `https://` к любому хосту; `http://` — только loopback,
  а к RFC1918/link-local/CGNAT/`.local` — только при явном согласии пользователя. Политика
  применяется дважды: при сохранении настроек и в `NetworkPolicyInterceptor` перед каждым
  запросом. TLS — системный trust store, пользовательские CA не принимаются.
* **Логи.** `MetadataLoggingInterceptor` пишет только method/path/status/время, и только в
  debug-сборке. `Redaction` маскирует `Authorization`, `Cookie`, `X-Api-Key` и query-параметры
  `token`, `auth_token`, `password`, `api_key`.
* **Бэкапы.** `allowBackup=false` + `dataExtractionRules` исключают `sharedpref`, `database`,
  `file` из облачного бэкапа и переноса устройства.

## 8. UI и адаптивность

* Material 3, тёмная и светлая темы, без dynamic color (предсказуемые цвета diff).
* `AdaptiveContent(maxWidth = …)` центрирует контент: 840 dp для списка/настроек, 1100 dp для
  diff, сообщения ограничены 720 dp — на планшете и в ландшафте текст не растягивается.
* Все интерактивные элементы ≥ 48 dp, кнопки инструментов — `IconButton`/`FilledIconButton`.
* Чат: `LazyColumn(reverseLayout = true)` — новые сообщения прилипают к нижнему краю, без
  ручных scroll-хаков; поле ввода учитывает IME (`imePadding`) и системную навигацию.
* Карточки инструментов и reasoning сворачиваемые; diff-строки моноширинные с
  горизонтальным скроллом.
* Навигация: sessions → chat → diff/settings, состояние экранов в ViewModel переживает
  поворот и переходы.

## 9. Встроенный сервер (опционально)

`LocalServerService` (foreground service, тип `specialUse`) + `LocalServerManager`:

1. Проверяет наличие proot-бинаря и rootfs; если нет — понятная ошибка со ссылкой на
   `server/README.md`.
2. Запускает `proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -lc "<command>"`
   с подстановкой `{port}`/`{rootfs}`; шаблон команды настраивается.
3. Читает stdout/stderr в кольцевой буфер (500 строк) и отдаёт в UI.
4. Health-check: `GET http://127.0.0.1:<port>/api/info`, затем `/config`; 200–499 считается
   «сервер жив» (401 = жив, но просит пароль).
5. Watchdog каждые 10 с проверяет процесс и health; при падении — `FAILED` и остановка сервиса.
6. Уведомление с кнопкой Stop; `onDestroy` убивает процесс (нет «сирот»).

Почему без бинарников: APK не может легально/технически тащить rootfs и proot без ABI-сборки,
лицензионных текстов и десятков мегабайт; в CI невозможно проверить arm64-процесс. Поэтому
фича — каркас с честной документацией и скриптами провижининга.

## 10. Тестирование

Юнит-тесты (JVM):

| Тест | Что проверяет |
| --- | --- |
| `SseParserTest` | многострочный data, комментарии-heartbeat, id, flush, reset |
| `UnifiedDiffParserTest` | несколько файлов/хунков, номера строк, CRLF, бинарные, мусор |
| `EventMapperTest` | v2 и legacy фикстуры, tool lifecycle, permissions, global envelope |
| `ChatStreamReducerTest` | дельты, авторитетный текст, мерж инструментов, legacy-кумулятивный текст |
| `NetworkPolicyTest` | https/http-политика, приватные диапазоны, редактирование секретов |
| `MarkdownLiteTest` | кодовые блоки, незакрытый fence, параграфы |
| `FormattersTest` | относительное время и утилиты строк |

CI прогоняет `testDebugUnitTest` и `assembleDebug`, складывает APK и отчёты в артефакты.

## 11. Дальнейшие шаги

См. [TODO.md](TODO.md). Ключевое: полноценный офлайн-режим (Room), вложения, PTY-терминал,
пуш-уведомления о permission-запросах, доведение встроенного сервера до рабочего состояния
(сборка proot + rootfs + проверка на arm64-устройстве).
