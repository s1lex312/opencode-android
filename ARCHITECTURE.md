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

**Режимы поставки.** Приложение по умолчанию работает автономно (**Local mode**): само
разворачивает proot-окружение с rootfs и `opencode` и запускает сервер на телефоне, а клиент
подключается к `http://127.0.0.1:4096`. Подключение к внешнему серверу (**Remote mode**) осталось
как равноправная опция для тех, у кого opencode уже работает на ноутбуке/сервере.

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

### Вариант D — гибрид A + B (**выбран**)

Нативный клиент (A) плюс встроенный proot-сервер (B), который является **режимом по умолчанию**:

* окружение (proot + rootfs + `opencode`) не входит в APK, а ставится при первом запуске из
  подписанного bundle (asset или HTTPS + SHA-256) — APK остаётся ~19 МБ;
* foreground service, health-check, автоперезапуск и логи делают локальный сервер полноценным,
  а не демонстрацией;
* ABI-риск (только arm64) изолирован в Local mode: Remote работает везде;
* если окружение не установлено, приложение честно предлагает Setup или Remote — краха нет.

**Итог:** вариант D реализован целиком; вариант A остаётся доступным как Remote mode, вариант C
(WebView) отклонён, вариант B в «чистом» виде (rootfs внутри APK) отклонён из-за размера APK и
лицензий.

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
│  EncryptedSecretStore (Keystore)  LocalSecrets  Redaction     │
│  NetworkPolicy                                                │
│  LocalRuntime ── EnvProvisioner ── TarGzExtractor (Local mode) │
│  LocalServerManager ── HealthProbe ── ServerLogStore          │
│  LocalServerService (foreground, specialUse, START_STICKY)    │
└───────────────────────────────────────────────────────────────┘
```

Принципы:

* **Один сокет на приложение.** `ServerConnectionManager` живёт в `AppContainer` (app-scope)
  и держит единственный SSE-поток, раздавая события через `SharedFlow`. Переходы между
  экранами не пересоздают соединение.
* **ViewModel — единственное место, где смешиваются состояния.** Репозитории возвращают типизированный
  `ApiResult`, а не бросают исключения в UI.
* **Чистая логика отделена от Android.** `SseParser`, `UnifiedDiffParser`, `EventMapper`,
  `ChatStreamReducer`, `NetworkPolicy`, `MarkdownLite.parse`, `Formatters`, а также
  `TarGzExtractor`, `EnvProvisioner`, `HealthProbe`, `RestartPolicy`, `ServerLogStore`,
  `LogLineParser` не зависят от `Context` и покрыты JVM-тестами.

## 4. Поток данных

### 4.1 Подключение

1. `SettingsRepository.current()` → `AppSettings`; для Local mode адрес берётся из
   `localServer.port`, auth — Basic с локальным паролем из Keystore.
2. В Local mode `LocalRuntime.awaitReadyForClient()` дожидается health-check локального сервера
   (или сообщает «не установлено», если окружения нет) — клиент не пытается биться в закрытый порт.
3. `NetworkPolicy.normalize()` проверяет схему/хост/порт (HTTPS или локальные адреса).
4. `ApiFactory` создаёт `ServerHttp` (OkHttp + `AuthInterceptor` + `NetworkPolicyInterceptor`).
5. Определение версии API:
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
| Режим (Local/Remote), адрес, auth-режим, модель, агент, политики, настройки локального сервера | DataStore Preferences | транзакционно, Flow из коробки |
| Пароль/токен Remote, пароль локального сервера | `EncryptedSharedPreferences` + Keystore | требование «только шифрованное хранилище» |
| Кэш списка сессий | JSON-файл в `cacheDir` | переживает перезапуск, не является вторым источником истины |
| Сообщения | только в памяти + перезапрос с сервера | избегаем рассинхронизации и роста БД |
| rootfs + opencode | `noBackupFilesDir/opencode-env` | сотни мегабайт, не должны попадать в бэкап |
| `proot` (исполняемый файл) | `applicationInfo.nativeLibraryDir` (`libproot.so`) | с Android 10 SELinux запрещает `execve()` из приватной папки приложения: бинарь обязан лежать там, куда его распаковывает установщик пакета (`jniLibs`, `extractNativeLibs=true`) |
| Лог сервера | `opencode-env/logs/server.log` | выживает при смерти процесса, ротация ≤512 КБ |

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

## 9. Local mode: «всё на телефоне»

Цель: установил APK — и пользуешься, без ПК, Termux и внешнего `opencode serve`. Root не нужен:
proot — это user-space `chroot`, всё живёт в песочнице приложения.

### 9.1 Компоненты

```
┌───────────────────────────── Android-приложение (UID приложения) ─────────────────────────────┐
│                                                                                               │
│  Compose UI ── SetupScreen ── LogsScreen ── Sessions/Chat/Diff/Settings                       │
│      │              │                                                                          │
│      ▼              ▼                                                                          │
│  SetupViewModel   LocalRuntime ──────────────────────────────────────────┐                    │
│                        │  1. пароль (Keystore)                          │                    │
│                        │  2. провижининг окружения                      │                    │
│                        │  3. запуск + health                            │                    │
│                        │  4. connect()                                  │                    │
│                        ▼                                                ▼                    │
│  EnvProvisioner                        LocalServerManager        ServerConnectionManager     │
│   • манифест (assets/…)                 • RestartPolicy            • OkHttp + SSE            │
│   • ArtifactSource (HTTP/asset)         • HealthProbe (GET /)       • NetworkPolicy          │
│   • SHA-256 (streaming)                 • ServerLogStore (файл)     • V2Api / LegacyApi       │
│   • TarGzExtractor (ustar/pax/GNU)      • LogLineParser                                     │
│   • staging → atomic swap                                                                      │
│                        │                                                ▲                    │
│                        ▼                                                │                    │
│  noBackupFilesDir/opencode-env/{rootfs/,installed.json,logs/,tmp/}      │
│  nativeLibraryDir/libproot.so (+ proot-libs, loader) — единственный exec│                    │
│                        │                                                │                    │
│  LocalServerService (foreground, specialUse, wake lock, START_STICKY) ──┘                     │
└───────────────────────────────────┬───────────────────────────────────────────────────────────┘
                                    │ ProcessBuilder
                                    ▼
        proot -0 --kill-on-exit -r rootfs -b /dev -b /proc -b /sys -b resolv.conf
              -w /root /usr/bin/env HOME=/root PATH=… OPENCODE_PASSWORD=*** 
              /bin/sh -lc "opencode serve --hostname 127.0.0.1 --port 4096"
                                    │
                                    ▼
                    HTTP/SSE на http://127.0.0.1:4096
```

### 9.2 Провижининг окружения

1. **Манифест**: `assets/local-env.json` (`version`, `abi`, `url`, `sha256`, `sizeBytes`). Поля URL и
   SHA-256 можно переопределить на экране Setup — тогда bundle скачивается по HTTPS.
2. **Источник**: `HttpArtifactSource` (OkHttp, без глобального таймаута) или `AssetArtifactSource`
   (bundle внутри APK). Для скачивания SHA-256 обязателен; без него установка отклоняется.
3. **Загрузка/копирование** в `.download/bundle.tar.gz` потоком с подсчётом байт и SHA-256
   «на лету» — файл не читается дважды и не держится в памяти.
4. **Проверка** хеша (сравнение, устойчивое к формату записи).
5. **Распаковка** в `.staging/` (`TarGzExtractor`: ustar, GNU longname, pax, symlink/hardlink,
   защита от path traversal, права на исполнение).
6. **Установка**: `proot` → `opencode-env/proot/proot` (0755), `rootfs/` → `opencode-env/rootfs`,
   запись `installed.json`. Старое окружение удаляется только после успешной распаковки — при
   ошибке остаётся прежняя рабочая версия.
7. **Очистка** (Setup → Clear) удаляет rootfs, proot, staging, скачанный архив, лог и маркер.

Состояние (`ProvisionState`) наблюдается UI: `CHECKING → DOWNLOADING → VERIFYING → EXTRACTING →
INSTALLING → READY|FAILED`, с прогрессом, байтами и текстом ошибки.

### 9.3 Жизненный цикл сервера

```
STOPPED ──start()──▶ STARTING ──health ok──▶ READY ──stop()──▶ STOPPING ──▶ STOPPED
                        │  ▲                    │
                        │  └── backoff ─────────┘   (падение процесса / 3 неудачных health)
                        └── лимит попыток ──▶ FAILED
```

- **Запуск**: `proot -0 --kill-on-exit -r <rootfs> -b /dev -b /proc -b /sys -b <resolv.conf>:/etc/resolv.conf
  -w /root … /bin/sh -lc "opencode serve --hostname 127.0.0.1 --port {port}"`.
  Пароль — в окружении дочернего процесса (`OPENCODE_PASSWORD`), не в argv.
- **Health-check**: `GET /`, затем `/api/info`, `/config`; `200–499` = сервер отвечает (401 — тоже
  «жив»). Опрос каждую секунду до 180 с на старте, затем каждые 5 с.
- **Перезапуск**: `RestartPolicy` — 1s/2s/4s/8s/16s (кап 30 s), не более 5 попыток; после лимита
  `FAILED` с текстом и логом. Пользователь может отключить автоперезапуск в настройках.
- **Остановка**: `stop()` отменяет супервизор, убивает процесс (SIGTERM → SIGKILL через 5 с),
  пишет маркер в лог, состояние `STOPPED`.
- **Логи**: `ServerLogStore` — файл `opencode-env/logs/server.log` с ротацией (≤512 КБ, хвост
  256 КБ), плюс кольцевой буфер 500 строк для UI. `LogLineParser` распознаёт «listening …:port»,
  `EADDRINUSE`, `proot error`, код выхода — используется для диагностики и подсветки в UI.

### 9.4 Фоновый режим и Doze

- `LocalServerService` — foreground service с типом `specialUse`; `START_STICKY`, `stopWithTask=false`:
  после сворачивания/свайпа и даже после убийства процесса система перезапускает сервис, а тот
  поднимает сервер.
- Partial wake lock на время работы сервиса (освобождается в `onDestroy`) — иначе proot может не
  успеть ответить в Doze.
- Уведомление показывает состояние (`Starting…`, `Listening on 127.0.0.1:4096`, `Failed`) и даёт
  Restart/Stop; на Android 13+ приложение просит `POST_NOTIFICATIONS`.
- Setup предлагает отключить оптимизацию батареи (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) —
  по желанию, не обязательно.

### 9.5 Переключение Local/Remote

`ConnectionMode` хранится в DataStore. Клиент всегда берёт `effectiveServerUrl`:
в Local — `http://127.0.0.1:<port>` + Basic с локальным паролем, в Remote — сохранённый адрес и
секрет. При Local клиент ждёт готовности сервера (`LocalRuntime.awaitReadyForClient`), а не падает
сразу: пока proot стартует, соединение переходит в `Connecting`.

### 9.6 Политика обновлений окружения

- Версия окружения — в манифесте (`version`), установленная копия сравнивается с манифестом при
  открытии Setup; при расхождении показывается «доступно обновление».
- Обновление = повторный провижининг (скачивание → проверка → staging → swap). Откат — вручную:
  старое окружение удаляется только после успешной распаковки, но не хранится как вторая копия
  (экономия ~400 МБ).
- Bundle версионируется по дате (`YYYY.MM`) + суффикс сборки; SHA-256 публикуется вместе с
  артефактом, приложение отказывается ставить bundle с несовпадающим хешем.
- Совместимость: ABI проверяется по манифесту (ожидается `arm64-v8a`), отсутствие `/bin/sh` в
  rootfs или `proot` в архиве — ошибка установки, а не «тихая» поломка.

## 10. Контракт API: что именно вызывается

Все пути и тела сверены с `packages/protocol/openapi.json` сервера opencode 2.x и с типами
`@opencode-ai/sdk` (legacy). Ниже — фактический список, который использует клиент.

| Назначение | v2 (`ApiFlavor.V2`) | legacy (`ApiFlavor.LEGACY`) |
| --- | --- | --- |
| Определение версии | `GET /api/info` (`ServerInfo {version, pid, urls, paths.tmp}`) | `GET /config` |
| Список сессий | `GET /api/session?limit&order` → `{data:[Session.Info], cursor}` | `GET /session` |
| Создание / удаление | `POST /api/session {title}` → `{data:Session.Info}`; `DELETE /api/session/{id}` | `POST /session`; `DELETE /session/{id}` |
| Сообщения | `GET /api/session/{id}/message?limit&order=asc` → `{data:[Session.Message.Info]}` | `GET /session/{id}/message?limit=` |
| Отправка промпта | `POST /api/session/{id}/model {model:Model.Ref}` → `POST …/agent {agent}` → `POST …/prompt {text}` | `POST /session/{id}/prompt_async {parts, model:{providerID,modelID}}` |
| Прерывание | `POST /api/session/{id}/interrupt` | `POST /session/{id}/abort` |
| Разрешения | `POST /api/session/{id}/permission/{requestID}/reply {decision}` (`once\|always\|reject`) | `POST /session/{id}/permissions/{permissionID} {response}` |
| Diff | `GET /api/session/{id}/diff` → `{data:[FileDiff.Info{file,patch,additions,deletions,status}]}` | `GET /session/{id}/diff` |
| Модели | `GET /api/model` → `{location, data:[Model.Info]}` | `GET /config/providers` |
| События | `GET /api/event` (`{id, created, type, data}`) | `GET /event` (`{type, properties}`) |

Особенности, которые пришлось учесть:

* `/api/info` возвращает объект **без** обёртки `data`, а списки — в `{data: …}`; парсер
  поддерживает оба варианта.
* Сообщение пользователя (`type: "user"`) несёт поле `text`, а ассистент — массив `content`
  (`text`/`reasoning`/`tool`); у `tool` состояние `state.status` = `streaming|running|completed|error`.
* У `session.message.shell` поле `output` — это объект `{output, cursor, size}`, а не строка.
* `session.status.status` — объект `{type: idle|busy|retry, message?}`; `busy` выводится из `type`.
* События `session.text.delta` содержат `{sessionID, assistantMessageID, ordinal, delta}`,
  инструменты — `{sessionID, assistantMessageID, id, name|input|content|metadata}`.
* `/api/permission/request` (список активных запросов) клиентом пока не используется: разрешения
  приходят событием `permission.asked` (в планах — досинхронизация при подключении).

## 11. Тестирование

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
| `TarGzExtractorTest` | файлы/каталоги/симлинки, GNU longname, path traversal, прогресс |
| `EnvProvisionerTest` | установка, проверка SHA-256, отказ без хеша, дружелюбные ошибки, очистка, маркер |
| `EnvManifestTest` | парсинг манифеста, snake_case, мусор, round-trip |
| `HealthProbeTest` | порядок проб `GET /` → `/api/info` → `/config`, 401/5xx, отсутствие ответа |
| `RestartPolicyTest` | экспоненциальный backoff и лимит попыток |
| `ServerLogStoreTest` | запись, хвост, ротация, очистка |
| `LogLineParserTest` | «listening», EADDRINUSE, proot-ошибки, коды выхода |
| `LocalSecretsTest` | генерация и переиспользование пароля локального сервера |

CI прогоняет `testDebugUnitTest` и `assembleDebug`, складывает APK и отчёты в артефакты, а также
проверяет пайплайн сборки bundle (`--mode stub`) и пытается собрать настоящий arm64-артефакт.

## 12. Дальнейшие шаги

См. [TODO.md](TODO.md). Ключевое: публикация подписанного arm64-bundle и проверка Local mode на
реальном устройстве, вложения и PTY-терминал, уведомления о permission-запросах, офлайн-история
(Room), Shizuku-интеграция как опция.
