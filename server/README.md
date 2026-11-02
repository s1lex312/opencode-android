# Bundle окружения для Local mode

`server/` — про то, как собрать **bundle**, который приложение ставит себе на телефон, и как
устроен запуск. Приложение работает без root: proot — user-space `chroot`, всё живёт в песочнице
приложения.

## Что такое bundle

Один файл `opencode-env-<version>-arm64.tar.gz`:

```
proot                          статический (или bionic-совместимый) arm64 proot
proot-libs/*.so                опционально: библиотеки, если proot динамический
                               (приложение кладёт их в opencode-env/proot/lib)
rootfs/                        минимальный Ubuntu/Debian arm64
rootfs/bin/sh                  обязателен (проверяется при установке)
rootfs/usr/local/bin/opencode  standalone-бинарь opencode для linux arm64
rootfs/etc/resolv.conf         заготовка DNS (приложение подменяет своим файлом)
```

Рядом публикуется `manifest.json`:

```json
{
  "version": "2026.11",
  "abi": "arm64-v8a",
  "url": "https://example.com/opencode-env-2026.11-arm64.tar.gz",
  "sha256": "…",
  "sizeBytes": 123456789
}
```

Приложение отказывается ставить bundle при несовпадении SHA-256 или при отсутствии хеша для
скачивания.

## Сборка

```bash
# из скачанных upstream-частей (ubuntu-base + opencode) и своего proot
./server/scripts/build-rootfs.sh --mode full \
    --output dist --version 2026.11 \
    --proot ./proot-aarch64 --download-base --download-opencode

# из своих файлов
./server/scripts/build-rootfs.sh --mode full \
    --output dist --version 2026.11 \
    --proot ./proot-aarch64 \
    --rootfs ./ubuntu-base-22.04.5-base-arm64.tar.gz \
    --opencode ./opencode

# проверка: целостность + обязательные элементы
./server/scripts/verify-artifact.sh dist/opencode-env-2026.11-arm64.tar.gz "$(sha256sum dist/opencode-env-2026.11-arm64.tar.gz | cut -d' ' -f1)"

# быстрая проверка самого пайплайна (нерабочий stub, без сети и root)
./server/scripts/build-rootfs.sh --mode stub --output dist
```

Скрипт печатает SHA-256, размер и готовые команды для обоих способов доставки.

## Доставка

| Способ | Как | Плюсы / минусы |
| --- | --- | --- |
**Готовый bundle уже собирается в CI**: workflow `.github/workflows/android.yml`, job
`Rootfs arm64 bundle`, артефакт **`rootfs-arm64-bundle`** (14 дней хранения). Внутри —
`opencode-env-<version>-arm64.tar.gz`, `manifest.json` и `.sha256`. Скачайте его из вкладки
Actions и используйте как asset или опубликуйте по HTTPS.

| Внутри APK | `cp dist/opencode-env-*.tar.gz app/src/main/assets/local-env.tar.gz`, обновить `assets/local-env.json` (`sha256`, `version`) | Работает офлайн, но APK растёт на ~120 МБ |
| По URL | Загрузить tarball в GitHub Release, на экране Setup вставить `https://` URL + SHA-256 | APK ~19 МБ, прогресс скачивания; нужен интернет при установке |

`assets/local-env.json` по умолчанию содержит пустой `url` и `"version": "unset"` — приложение
покажет Setup с предложением установить bundle или переключиться в Remote mode.

## Где взять proot для arm64

`proot` требует `libtalloc` под целевую архитектуру. Варианты:

0. **Termux-пакет (используется в CI, самый простой)**: `proot` из
   `packages.termux.dev/apt/termux-main` — сборка под arm64 Android (bionic). Распакуйте `.deb`
   (`ar x pkg.deb && tar xf data.tar.*`) и передайте:

   ```bash
   ./server/scripts/build-rootfs.sh --mode full --output dist --version 2026.11 \
       --proot ./termux/data/data/com.termux/files/usr/bin/proot \
       --proot-libs ./proot-libs \
       --download-base --download-opencode
   ```

   `proot-libs` — каталог с `libtalloc.so.2`, `libandroid-support.so` и другими зависимостями
   (можно скопировать весь `usr/lib` из тех же `.deb`). Приложение выставляет
   `LD_LIBRARY_PATH=<env>/proot/lib`, поэтому такой proot работает без Termux рядом.

1. **Кросс-сборка** (что пробовали в CI до Termux-варианта; требует статического `talloc`,
   которого нет в Ubuntu):
   собрать `talloc` под `aarch64-linux-gnu`, затем `proot-me/proot`:

   ```bash
   curl -fsSLO https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz && tar xf talloc-2.4.2.tar.gz
   (cd talloc-2.4.2 && ./configure --host=aarch64-linux-gnu --disable-python --without-gettext --prefix="$PWD/../talloc-arm64" && make -j && make install)
   git clone --depth 1 https://github.com/proot-me/proot && cd proot/src
   PKG_CONFIG_PATH=../../talloc-arm64/lib/pkgconfig make CC=aarch64-linux-gnu-gcc CFLAGS="-static -O2" LDFLAGS="-static"
   ```

   Полученный статический бинарь кладётся как `--proot`. Важно: он должен быть собран под
   arm64 (aarch64), а не под x86_64 хоста.

2. **Своя Android/bionic-сборка** (NDK, как в termux-packages): если proot динамический —
   положите нужные `.so` в `proot-libs/` внутри bundle.

3. **Готовый статический бинарь** из доверенного источника — обязательно проверьте на устройстве
   (`docs/SMOKE-TEST.md`, раздел 7).

## Запуск внутри приложения

```
proot -0 --kill-on-exit -r <rootfs> -b /dev -b /proc -b /sys \
      -b <files>/resolv.conf:/etc/resolv.conf -w /root \
      /usr/bin/env HOME=/root PATH=/usr/local/bin:/usr/bin:/bin TERM=dumb LANG=C.UTF-8 \
      OPENCODE_PASSWORD=<сгенерированный пароль> \
      /bin/sh -lc "opencode serve --hostname 127.0.0.1 --port 4096"
```

- пароль генерируется приложением, хранится в Keystore-backed хранилище и передаётся только через
  окружение процесса;
- health-check — `GET /` (fallback `/api/info`, `/config`), 200–499 = сервер жив;
- автоперезапуск: 1→2→4→8→16 с, максимум 5 попыток (настраивается);
- лог: `noBackupFilesDir/opencode-env/logs/server.log` (ротация ≤512 КБ) + экран **Setup → Logs**.

## Куда ставится окружение

```
/data/data/<applicationId>/noBackupFilesDir/opencode-env/
  proot/proot
  proot/lib/*.so        # если bundle содержал proot-libs/
  rootfs/…
  installed.json        # манифест установленной версии
  logs/server.log
  resolv.conf
  .download/ .staging/   # временные, удаляются после установки
```

Ничего не пишется в общие каталоги, root не нужен, всё удаляется кнопкой **Setup → Clear**.

## Типичные проблемы

| Симптом | Причина / решение |
| --- | --- |
| «The environment bundle was not found» | bundle не в assets и URL не задан — соберите и опубликуйте его |
| «Checksum mismatch» | скачался не тот файл или манифест устарел; сверьте SHA-256 |
| «Not enough free space» | нужно ~1 ГБ свободного места на распаковку и запас |
| `proot error: execve("/bin/sh"): No such file or directory` | proot не для этой архитектуры или rootfs битый |
| `EADDRINUSE` | порт 4096 занят другим приложением/процессом — смените порт в настройках |
| `CANNOT LINK EXECUTABLE "proot": library "libtalloc.so.2" not found` | нет `proot-libs/` в bundle — соберите его с `--proot-libs` |
| «Server did not become healthy» | смотрите лог: часто нет `opencode` в rootfs или нет прав на исполнение |
| Сервер падает в фоне | отключите оптимизацию батареи для приложения (Setup → Disable) |
| Нет сети внутри rootfs | публичный DNS недоступен; замените `resolv.conf` (см. TODO) |

## Ограничения

- Только arm64-устройства.
- Релиз bundle публикуется вручную: CI собирает артефакт `rootfs-arm64-bundle`, но GitHub Release
  создаётся отдельно (TODO).
- proot/talloc распространяются по GPLv2/LGPL — при публикации bundle приложите их лицензии.
- Shizuku не требуется и не используется.
