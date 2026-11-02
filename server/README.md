# Встроенный proot-сервер (экспериментально)

Эта папка — про провижининг локального сервера opencode, который запускается **на самом
Android-устройстве** внутри proot, без root. Приложение умеет его запускать
(`LocalServerService` + `LocalServerManager`), но **не содержит** ни proot, ни rootfs.

## Почему бинарников нет в APK

* Размер: минимальный Ubuntu arm64 rootfs с Bun и opencode — 150–300 МБ.
* ABI: нужен именно arm64; универсальный APK раздуется, а на x86_64/armeabi работать не будет.
* Лицензии: rootfs (Ubuntu/Debian) и сторонние пакеты требуют отдельных текстов лицензий.
* Проверка: в CI нет arm64-устройства, а эмуляция proot на x86 не даёт достоверного результата.

Поэтому это осознанный «bring your own runtime»: разработчик собирает rootfs и proot,
кладёт их на устройство, указывает пути в настройках приложения.

## Что нужно

* arm64 Android-устройство (Android 8+), включённая отладка по USB;
* `adb`;
* **статически собранный** arm64 `proot` (динамический бинарник не запустится: в Android нет
  glibc, а rootfs-библиотеки не подхватываются до входа в rootfs);
* rootfs Ubuntu/Debian arm64 с установленным opencode (Bun или npm).

## Шаг 1. Сборка rootfs (на Linux-хосте)

```bash
# Ubuntu arm64 rootfs c opencode внутри; нужен root/sudo на хосте
sudo ./server/scripts/build-rootfs.sh \
  --output ./out/rootfs-arm64.tar.gz \
  --install-opencode

# без установки opencode (добавите позже вручную внутри proot)
sudo ./server/scripts/build-rootfs.sh --output ./out/rootfs-arm64.tar.gz
```

Скрипт использует `debootstrap` (если есть) или официальный Ubuntu base tarball и, при
`--install-opencode`, запускает установку через `qemu-user-static` + `proot` на хосте.
Внимательно прочитайте скрипт: он делает `mount`/`chroot`-подобные операции на вашем хосте.

## Шаг 2. proot

Вариант A — собрать статический proot под arm64:

```bash
# на arm64-хосте или через кросс-компиляцию musl
git clone https://github.com/proot-me/proot.git
cd proot/src
make CC="aarch64-linux-musl-gcc" LDFLAGS="-static"
```

Вариант B — взять готовый статический бинарник из доверенного источника (например, из
`termux-packages`/`proot-distro`) и переименовать в `libproot.so`.

Положите бинарник одним из способов:

1. **В APK** (проще всего): `app/src/main/jniLibs/arm64-v8a/libproot.so` — Android упакует
   его в `nativeLibraryDir` с правом на исполнение. Файл не попадёт в git (см. `.gitignore`
   рекомендации ниже).
2. **На устройство**: `filesDir/proot/proot`, затем `chmod 700` (см. скрипт ниже).

> Имя `lib*.so` нужно, потому что Android устанавливает как исполняемые только файлы из
> `nativeLibraryDir` (для `.so`). Не добавляйте бинарник в git — это артефакт.

## Шаг 3. Провижининг на устройстве

```bash
# 1) proot + rootfs
PROOT_BIN=./out/proot-arm64-static \
ROOTFS_TARBALL=./out/rootfs-arm64.tar.gz \
APP_ID=ai.opencode.android \
./server/scripts/install-on-device.sh

# для debug-сборки applicationId другой:
PROOT_BIN=./out/proot-arm64-static \
ROOTFS_TARBALL=./out/rootfs-arm64.tar.gz \
APP_ID=ai.opencode.android.debug \
./server/scripts/install-on-device.sh
```

Скрипт кладёт rootfs в `/data/data/<app>/files/rootfs` (через `run-as`, поэтому работает
только с debuggable APK) и proot в `/data/data/<app>/files/proot/proot`.

## Шаг 4. Настройки в приложении

**Settings → Local server (experimental)**:

| Поле | Значение |
| --- | --- |
| Rootfs path | `/data/data/ai.opencode.android/files/rootfs` (пусто = `filesDir/rootfs`) |
| proot path | `/data/data/ai.opencode.android/files/proot/proot` (пусто = `libproot.so` из APK) |
| Port | `4096` |
| Server command | `opencode serve --hostname 127.0.0.1 --port {port}` |

Нажмите **Save local settings**, затем **Start**. Приложение:

1. проверит наличие proot/rootfs и напишет понятную ошибку, если чего-то нет;
2. запустит `proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /bin/sh -lc "<command>"`;
3. будет опрашивать `http://127.0.0.1:4096/api/info` (fallback `/config`) до 120 секунд;
4. покажет статус `Running (healthy)` и логи (последние 500 строк);
5. уведомление в шторке с кнопкой **Stop**; сервис — foreground (`specialUse`).

После `Running` можно вернуться на экран сессий: адрес `http://127.0.0.1:4096` — это тот же
сервер, приложение подключится к нему как к обычному `opencode serve`.

## Проверка вручную

```bash
adb shell run-as ai.opencode.android.debug ls -la files/rootfs/bin/sh
adb shell run-as ai.opencode.android.debug ls -la files/proot/proot
curl -sS http://127.0.0.1:4096/api/info   # на устройстве, например через adb shell (toybox curl нет — используйте приложение)
```

## Типичные проблемы

| Симптом | Причина / решение |
| --- | --- |
| `proot binary not found` | не указан путь и нет `libproot.so` в APK; см. шаги 2–3 |
| `Rootfs directory not found` | rootfs не распакован или указан неверный путь |
| `opencode exited with code 127` | внутри rootfs нет `/bin/sh` или `opencode` в `PATH`; проверьте rootfs |
| `error: execve("/bin/sh"): No such file or directory` | динамический proot/битый rootfs; нужен статический proot |
| Health-check timeout | сервер слушает не на 127.0.0.1/не тот порт; проверьте команду и логи |
| Процесс убивается в фоне | Doze/агрессивный OEM-менеджер батареи; отключите оптимизацию для приложения |

## Ограничения

* Только arm64-устройства.
* Нет автоматической распаковки rootfs из tarball в приложении (в TODO).
* `opencode` внутри rootfs должен быть предустановлен (версия — любая, поддерживаемая
  клиентом: 2.x или legacy 0.x/1.x).
* Shizuku-интеграция не реализована (в TODO).
