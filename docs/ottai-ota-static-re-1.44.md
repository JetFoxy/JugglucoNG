# Ottai OTA: статический RE (Blutter, com.ottai.tag 1.44 arm64) — результаты

Статус: P1 (статический RE) — основная часть закрыта, 2026-10-26.
План: `docs/ottai-firmware-ota-re-plan.md`. Артефакты (вне репо):
`~/Projects/Dia/ottai-ota/`.

## 0. Как получен результат

- `Ottai_1.50.0_APKPure.xapk` содержит только `armeabi-v7a` (как и 1.38): Blutter
  ARM32 не умеет. Дополнительно **1.50 обфусцирован на уровне Dart**:
  имена вида `init:_TJe@601043202`, `_fph`, `_qna`; символов `CgmOTA`, `startCgmOTA`,
  `OTAUpgradeModel`, `_startDeviceOtaUpgrade` в `libapp.so` нет (в 1.38 были).
  При этом константы `/ota/upgradeVersion/judge`, `03A617EB`, `V1.8.`, `.GBL`,
  `writeOTAControl:`, `download_ota_package_*`, `update_ota_status_*` в 1.50 на месте.
  Вывод: код тот же, имена переименованы → 1.50 для чтения кода не годится.
- Рабочий статический таргет: **`~/Downloads/Ottai_mod_1_44.apk`** = `com.ottai.tag`
  (CN-сборка) 1.44, `lib/arm64-v8a/libapp.so` 25 МБ, Dart 3.10.9, **без обфускации**,
  флаги снапшота `product, compressed-pointers, no-shared_data, arm64`.
  Тот же модуль `sy_embeded`, те же константы, что в 1.44/1.50 `com.ottai.seas`.
- Blutter (worawit/blutter, коммит 4a60ac6) + Dart SDK 3.10.9 собран локально.
  Понадобился фикс ICU (Homebrew `brew --prefix icu4c` → `icu4c@79` без libicuuc;
  в `scripts/CMakeLists.txt` добавлен откат на `icu4c@78`) и правка
  `ICU_INCLUDE_DIR` в CMakeCache.
- Выход: `~/Projects/Dia/ottai-ota/work/blutter-1.44/asm/` — листинги по исходным
  файлам (`package:sy_embeded/...`), `pp.txt` — дамп пула объектов.
  Адреса ниже (`0x…`) — адреса в `libapp.so` 1.44 arm64, для трассировки.

Ограничение: APK модифицированный, не из стора. Идентичность протокола с
`com.ottai.seas` подтверждена константами и именами состояний, но финальную
сверку даёт только P2 (HCI-лог).

## 1. GATT-карта Ottai (найдена целиком)

`CgmCharacteristicType` (`pp.txt`, объекты `Obj!CgmCharacteristicType`) — 21 значение,
порядок индексов совпадает с порядком UUID в пуле (`pp+0x278b0…0x27950`).
Проверено по якорям: `softVersion` = `2a28`, `currentTime` = `2a2b`,
`ctlDevice` = `6aa799b6` (ACTIVATE_CMD), `activeDuartion` = `b8fd9848`.

| # | enum | UUID | Замечание |
|---|---|---|---|
| 0 | deviceInfo | `0000180a-0000-1000-8000-00805f9b34fb` | Device Information |
| 1 | softVersion | `00002a28-0000-1000-8000-00805f9b34fb` | Software Revision (ASCII) |
| 2 | errorInfo | `cb627922-4e79-42e3-b107-a10e816f6caa` | |
| 3 | currentTime | `00002a2b-0000-1000-8000-00805f9b34fb` | Current Time |
| 4 | serviceAuth | `e06e1d43-1319-4ebf-94b0-5b0e5313b1f4` | |
| 5 | authHost | `1756ef6e-884b-4eb0-b646-f04ab18408f9` | |
| 6 | authDev | `86805092-92b5-4d8c-9d73-0785ff6f9147` | |
| 7 | authFlag | `785022c6-08c0-48af-ad17-684bb889aa83` | |
| 8 | serviceCGM | `0000181f-0000-1000-8000-00805f9b34fb` | |
| 9 | monitorGlucose | `00002aa7-0000-1000-8000-00805f9b34fb` | |
| 10 | glucoseRecord | `69e4f45f-a180-422c-83c0-324146402112` | |
| 11 | requestByCount | `ccecb015-6750-41fd-ba78-3fb77d350574` | |
| 12 | convertCmd | `d78d0706-c775-448d-8a78-01215e7c2e11` | «вендорская команда» из плана |
| 13 | selfTest | `cb627922-4e79-42e3-b107-a10e816f5b99` | |
| 14 | mackey | `12347922-4e79-42e3-b107-a10e816f5b99` | |
| 15 | serviceOTA | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | SiLabs OTA |
| 16 | otaControl | `f7bf3564-fb6d-4e53-88a4-5e37e0326063` | |
| 17 | otaData | `984227f3-34fc-4045-a5d0-2c581f81a153` | |
| 18 | ctlDevice | `6aa799b6-b374-4148-8f36-6d440c0ec203` | ACTIVATE_CMD |
| 19 | ctlGluDataIdx | `c8693b6f-f850-44cb-b9b4-ac87ed962bac` | не писать (destructive) |
| 20 | activeDuartion | `b8fd9848-0ccd-423f-bd34-2419aa7ea004` | maxActive, 4 байта |

Сервисные имена для логов: `device_info`, `service_auth`, `service_CGM`, `service_OTA`
(`pp+0x27860`), характеристики: `soft_version`, `error_info`, `current_time`, `auth_host`,
`auth_dev`, `auth_flag`, `new_glucose`, `glucose_record`, `request_by_count`, `cmd`,
`self_test`, `mac_key`, `ota_control`, `ota_package`, `ctl_device`, `ctl_glu_data_idx`,
`active_duartion` (опечатка в коде).

## 2. Флоу активации и место OTA (ответ: вариант A)

`CgmBindFlowManger` (`asm/sy_embeded/src/flow/cgm_bind_manger.dart`) — состояния
(полный enum `CgmBindFlowState`, 89 значений, `_pushState @0xc0b6cc`) идут в порядке:

```
nfc_* → ble_permit → ble_scan/connect/discover_service
  → read_device_version_profile → auth
  → check_active
      ├─ уже активирован → check expired → дальше
      └─ не активирован:
           upgrade_check (GET /ota/upgradeVersion/judge)
             ├─ requiredUpgrade=true → download_ota_package → ota_start/success/error
             │                        → update_ota_status → **rescan 60 с** → флоу с начала
             └─ иначе → upgrade_check_success / upgrade_count_limt → дальше
  → set_device_coefficient → write_calibration_parameter
  → set_active_duration (b8fd9848) → set_retain_duration
  → active_start (ctlDevice, ACTIVATE_CMD)
  → bind_device (POST /deviceBind/composite/bind)
```

Факты:

- OTA выполняется **до** записи `maxActive` и **до** `ACTIVATE_CMD` → в плане это
  вариант **A**, окно между OTA и активацией есть.
- OTA вообще не рассматривается для уже активированного сенсора: ветка
  `checkNeedDeviceUpgrade` вызывается только когда `check_active != true`
  (`cgm_bind_manger.dart` 0xc042a4 → 0xc042c8).
- После успешной OTA приложение делает `startScan(mac, 60 s, isFromBind: true)`
  (`_startOtaMiniFlow @0xc07638`, Duration = `0x3938700` мкс) и **перезапускает
  весь флоу с нуля** (scan → connect → discover → read version → auth → check_active).
  Никакой отдельной «пост-OTA» логики нет.
- Если OTA не удалась — `_startBleScan` не вызывается, флоу просто завершается
  (сенсор остаётся неактивированным; приложение показывает ошибку шага bind).
- Решение об OTA принимает **облако**; `requiredUpgrade=true` → OTA обязательна
  (то, что пользователь видит в офиц. приложении). `allowUpgrade=true &&
  requiredUpgrade=false` → OTA **не** делается, ставится состояние
  `upgradeCountLimt`, и текущий проход флоу на этом **завершается** (UI показывает
  экран «обновление доступно/лимит»); продолжение — только новым проходом.
  `downloadUrl == null` → `upgradeCheckError`, тоже конец прохода.

## 3. Транспорт OTA (SiLabs AppLoader)

`CgmOTA.startCgmOTA(path)` — `asm/sy_embeded/src/ota/cgm_ota.dart` @0xc07bec:

1. `CgmOTATool.readOTAFile(path)` — файл из `getApplicationDocumentsDirectory()`,
   проверка существования + `path.toUpperCase().endsWith(".GBL")` + `verifyFile(bytes)`
   (см. §4). При ошибке — `false`.
2. `requestMTU(mac)` → плагин просит MTU **247** (`0xf7`, `sy_bluetooth_plugin.dart`
   @0xc08448); берётся фактически согласованный.
3. `chunk = (mtu ~/ 4) * 4` → при 247 это **244** (= MTU-3).
4. `pushOTAProgressState(0.0)`.
5. `writeOtaControl([0x00])` — в `f7bf3564…`, **write-with-response**
   (`SyBluetoothPlugin.writeCharacteristic`). Если не `true` — выход с `false`.
6. Цикл `i = 0; i < len; i += chunk`: `writeOtaData(file.sublist(i, min(i+chunk, len)))`
   в `984227f3…`, тоже write-with-response, последовательно (await каждого).
   После каждого успешного чанка `pushOTAProgressState(i / len)` (в UI — `otaProgress`).
   Первая же ошибка → `false`, **без ретраев**.
7. `pushOTAProgressState(1.0)`.
8. `writeOtaControl([0x06])` → `true`.

Что важно:

- Никакой вендорской команды/авторизации непосредственно перед `0x00` нет:
  auth (`authHost/authDev/authFlag`, запись ключа в `mackey`) выполняется раньше,
  как обычный шаг флоу. `d78d0706` (convertCmd) в OTA-пути не участвует.
- Между чанками приложение выдерживает паузу из настроек (`write()` делает
  `Future.delayed(...)` после каждой записи; ключи `key_ble_*` приходят из
  `sdk_init` профиля, значения по умолчанию в коде не зашиты) — точный темп
  надо брать из P2.
- Финал — `0x06`, а не `0x03` как в классическом описании SiLabs
  («0x03 = upload finished»). Либо у Ottai AppLoader другой набор опкодов, либо
  `0x06` = «finish + reboot». Различие принципиально, закрывается HCI-логом.
- Перезагрузка сенсора ожидается **после** `0x06`: сразу после успеха флоу уходит
  в скан на 60 с и переподключается по тому же MAC (`isFromBind: true`).
  Меняется ли MAC/имя после ребута — статически не видно.

## 4. Проверка пакета `.gbl` (в приложении)

`CgmOTATool.verifyFile(bytes, startIndex = 0)` @0xc086c0 — рекурсивный обход GBL-тегов:

- `tagId` = 4 байта с начала тега, читаются **в обратном порядке**, в hex-строку;
- допустимые `tagId` (hex, как в коде): `03A617EB` (header), `5EA617EB` (bootloader),
  `F40A0AF4` (erase&prog), `F50909F5` (end), `F70A0AF7` (signature), `F60808F6`,
  `FC0404FC`, `FE0101FE`, `FD0303FD`, `FD0505FD`, `FD0707FD`
  (последние — группа encryption/metadata/secure-boot; точные имена по UG103.7
  не сверял);
- `tagLength` = 4 байта little-endian; переход к следующему тегу `start+4+len`;
  конец = ровно длина файла;
- если обход бросил исключение, но остаток файла целиком `0xFF` — считается валидным
  (допускается «стёртый» хвост).

**Хеша, CRC или проверки подписи в приложении нет.** Целостность/подпись проверяет
Gecko Bootloader на сенсоре по тегам signature/encryption.

## 5. Облако

| Что | Детали |
|---|---|
| Проверка версии | `GET /ota/upgradeVersion/judge?mac=<mac>&deviceVersion=<cachedVersion>&deviceType=cgm` (`cgm_api.dart` @0xc094c8) |
| Ответ | `OTAUpgradeModel.fromJson`: `downloadUrl`, `requiredUpgrade` (bool), `allowUpgrade` (bool), `errorCode` (`ota_upgrade_model.dart`) |
| `deviceVersion` в запросе | **из локального кеша** `EmbededVersionManger.getSoftwareVersionByMac(mac)`, а не чтение с сенсора |
| Скачивание | `downloadOTAFile(url)`: имя = последний сегмент URL, сохраняется в `getApplicationDocumentsDirectory()/<имя>`, если файла нет — `DioNet.downloadFile(url, path)`; повторно не качается |
| Отчёт | `POST /device/ota/upgrade` c `{mac, sourceVersion, targetVersion, upgradeStatus, remark}`; `upgradeStatus = 1` при успехе, `0` при ошибке; `targetVersion` = имя файла без `.gbl` (`_getFileName`), `sourceVersion` = версия из кеша (т.е. **старая**) |
| Ещё | `/device/getUranusVersion`, `/device/validateDeviceByMacV2` |

Следствие для P0: облачная `deviceVersion` после OTA обновится только когда
приложение снова прочитает `2a28` (`readSoftVersion`) в новом проходе флоу и
запишет в кеш/на сервер (`EmbededVersionManger.setTargetDeviceVersion`). Сам отчёт
об OTA версию не обновляет.

## 6. Версионные профили (важно для H2)

`SyEmbededVersionProfile.getVersionProfileFromSupport(version)` @0xbf4a98:
по префиксу `softVersion` выбирается профиль `V1.3. … V1.8.`
(фабрики `SyEmbededVersionProfile.v_1_3 … v_1_8`). Профиль = набор поддерживаемых
характеристик (`isSupportCharacter`), т.е. по факту — карта возможностей прошивки.

- `serviceOTA`/`otaControl`/`otaData` есть во **всех** профилях V1.3…V1.8.
- `activeDuartion` (`b8fd9848`) появляется только с **V1.4** — на более старой
  прошивке `maxActive` не пишется вовсе.
- `readSoftVersion` читает `2a28` и делает `createFromCharCodes` (ASCII), т.е.
  версия — строка вида `V1.x.y`.
- `setTargetDeviceVersion` сравнивает прочитанную версию с профилем: несовпадение →
  `readDeviceVersionProfileError` и флоу дальше не идёт. Значит список допустимых
  версий зашит в приложение — можно вытащить полный список поддерживаемых прошивок
  из 1.44 (следующий шаг, дешёвый).

## 7. Ответы на вопросы P1

| Вопрос плана | Ответ |
|---|---|
| Где OTA относительно `ACTIVATE_CMD` | До: `check_active → upgrade → coefficient → calibration → active_duration → ACTIVATE_CMD → bind`. Вариант A |
| Нужна ли переактивация после перезагрузки | После OTA флоу начинается заново целиком и доходит до `ACTIVATE_CMD`; сенсор к этому моменту ещё не активирован |
| Что пишется перед `0x00` | Ничего сверх обычного auth. `0x00` идёт сразу в `f7bf3564…` |
| Как ищется после ребута | Скан 60 с, `isFromBind: true`, переподключение по тому же MAC; полный ре-дискавери сервисов |
| Чанк / MTU / тип записи | MTU запрос 247, чанк `floor(mtu/4)*4` (244), write-with-response, последовательно, пауза из настроек, ретраев нет |
| Проверка пакета | Только обход GBL-тегов + расширение `.GBL`; хеша/подписи в приложении нет |
| `forceUpgrade` / лимиты | `requiredUpgrade` (обязательно), `allowUpgrade` (разрешено, но не обязательно → пропуск), `errorCode` |
| Что после OTA | Рескан 60 с → read `2a28` → auth → check_active → coefficient/calibration → maxActive → ACTIVATE_CMD → bind; затем `POST /device/ota/upgrade` (отчёт был ещё до рескана) |
| Потолок `maxActive` на новой прошивке | В приложении потолка нет: пишется 4-байтовое значение, **шифруется** `CgmAuth.encrypt` при необходимости и уходит в `b8fd9848`. Ограничение, если есть, — на стороне облака/прошивки; статически не закрывается, нужен P2/P4 |

## 8. Что осталось (только динамика, P2)

1. Семантика `0x06` vs `0x03` в конце и момент фактического разрыва соединения/ребута.
2. Реальный темп записи (пауза `key_ble_*`), поведение при обрыве на середине.
3. Меняется ли MAC/имя/adv-данные после ребута в AppLoader.
4. Потолок срока службы на новой прошивке (стоп-критерий) — только эксперимент.
5. Привязка `.gbl` к партии/MAC и наличие подписи (по самому файлу, P3).
6. Сверка протокола 1.44 (CN, mod) с 1.50 (`com.ottai.seas`) по HCI-логу.

## 9. Что это значит для P5

- Реализуемо точным повторением последовательности §3 (control `0x00` → чанки 244 Б
  write-with-response → control `0x06` → ожидание ребута → рескан/реконнект по MAC).
- Порядок в JugglucoNG: OTA вставляется туда же, где у офиц. приложения — **перед**
  записью `maxActive` и `ACTIVATE_CMD`; после OTA — переподключение и обычная
  активация с длинным `maxActive`.
- Проверять версию после OTA логично через `2a28` (read-only), как и планировалось в P0.
- Риск: подтверждение `0x00`/`0x06` и поведения при ребуте нужно получить до первой
  записи в сенсор; иначе высок шанс оставить устройство в AppLoader.
