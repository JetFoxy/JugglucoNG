# Direct CN Ottai firmware field test

This implements an experimental alternative to the official-app capture proposed in
[PR #727](https://github.com/ctqvva/JugglucoNG/pull/727). No official app is required on
the test phone. The sensor must be fresh/unactivated, with saved credentials and a signed-in
CN phone account. An already-managed, activated or ended sensor is refused.

## Test on the phone

1. Enable Android Bluetooth HCI snoop logging if available. This records **our own** traffic;
   it is diagnostic evidence, not an independent official-protocol reference.
2. Open Sensors → add Ottai. Sign in to the CN account, enter/scan the sensor code, and
   select its actual Bluetooth advertisement. NFC wake may be needed. Let the existing
   credential fetch/bootstrap finish, then export the credentials JSON for recovery.
3. **Do not press the normal Connect/activate button yet.** Choose **Check firmware
   (experimental)** below it. This stops the setup scanner and opens a separate BLE client,
   outside the managed-sensor list. It reads software revision `2a28`, checks the sensor's
   auth signature against the saved keys, authenticates, then reads the command status.
   Only status 0–2 can continue.
4. The check uses the actual BLE revision in the CN `/ota/upgradeVersion/judge` request.
   If an update is offered it downloads the package and displays the source/target versions,
   file size and SHA-256. **The check does not start a firmware transfer.**
5. Choose one explicit experiment:
   - **Write factory parameters only** refreshes cloud materials for the read revision,
     writes supported factory parameters, and leaves the firmware as it was.
   - **Install firmware** transfers the offered image. After a successful finish ACK it
     reconnects to the same Bluetooth address, requires the target revision from `2a28`,
     reauthenticates and confirms the sensor is still unactivated. It then refreshes the
     materials and writes the parameters supported by the installed revision.
6. On **Preparation complete**, close the dialog and use the normal Connect action to
   activate. The existing `maxActive` negotiation still runs there. No firmware-test path
   writes RTC, `maxActive`, retain duration or `ACTIVATE_CMD`.
7. Export the trace log, particularly `OttaiOTA`, `Ottai` and `AUTHWIRE` entries. Record the
   displayed revisions, result, and any diagnostic code. After activation, retain the normal
   activation/status/lifetime-readback logs and first readings for comparison.

Keep the dialog open and the phone near the sensor during installation. It keeps the screen
awake. It is a foreground field test, not a background updater. Android can still stop the
process, so an unfinished attempt is persisted before the first OTA write.

With one sensor, an OTA followed by parameter writes can establish that combined setup path.
It cannot isolate the effect of firmware from factory provisioning on early readings.

## Transfer and recovery

- CN 1.44's reported sequence is used: control `00`, exact file bytes on the data
  characteristic, then control `06`. Every write uses a response and waits for its callback.
  The finish byte is deliberately **not** guessed again as `03` on failure.
- Each write has a 60 ms delay, a conservative field-test setting rather than recovered
  official pacing. Chunks are at most 244 bytes and never exceed negotiated MTU minus 3;
  four-byte alignment is preserved except for the final chunk.
- Progress counts acknowledged bytes. A rejected write, false Android start result,
  timeout or unexpected disconnect stops the transaction; uncertain chunks are not retried.
- The exact validated package, original/target revisions, sensor ID, Bluetooth address and
  digest are stored atomically under the app's private `files/ottai-ota/` before transfer.
  No download URL or account credentials are placed in this journal.
- Reopen **Check firmware** after an interruption. A bootloader-only GATT can offer
  an explicit restart of that saved package **only on the recorded address**. It never
  scans neighbouring devices or picks a different image. Address changes and a corrupt or
  missing recovery package require investigation, rather than speculative writes.
- An unfinished journal blocks normal and forced activation. It is cleared only after
  successful version-appropriate preparation. If the old application still boots, the
  separate parameter-only action can finish preparation without completing the update.
- An OTA success report is attempted only after matching BLE version readback and a fresh
  authenticated unactivated status. Report failure does not substitute for sensor evidence.

## Validation and remaining hardware questions

Package parsing uses the Silicon Labs GBL v3 layout: 8-byte tag headers, `FC0404FC` end tag,
bounded lengths and the end CRC32. An erased `FF` tail is allowed. SHA-256 identifies the
downloaded bytes; it does not authenticate the manufacturer. Bootloader signature enforcement,
package compatibility and actual Ottai acceptance still require hardware evidence.

Factory provisioning follows #727's reported version gates:

- V1.6+ coefficients: `09 | count | float32 LE values | CRC16-Modbus LE`.
- V1.8 calibration: `0B | AES(session, float64 LE k | float64 LE b)`.
- Missing/nonfinite values stop preparation. In V1.8 both packets are constructed and
  validated before either factory write. No default calibration is invented.

Only the recovered V1.3–V1.8 profiles and version-named HTTPS `.gbl` offers are accepted.
Download redirects must remain HTTPS; cloud authorization headers are not sent to download
hosts. File size is limited to 2 MiB. Unknown formats fail before transfer.

The local Blutter artifacts cited by #727 were unavailable here. The `06` finish byte,
60 ms pacing, same-address reboot, cloud contract and factory-parameter behavior are therefore
field-test assumptions, not independently confirmed wire facts. A transfer ACK is not a
successful update: target-version readback is required. Existing operation beyond day 15 is
already established; firmware-specific changes to that behavior would be a regression question.

Tests exercise the exact controller used by the session with fake transport completions:
identity/status gates, no implicit installation, acknowledged chunk progression, rejection,
cancellation, duplicate/late callbacks, bootloader recovery, version verification, fresh
materials and parameter gates. They do not establish compatibility with a physical sensor.

References for the generic container/transport, not Ottai-specific opcode semantics:

- [Silicon Labs GBL format definitions](https://github.com/SiliconLabs/gecko_sdk/blob/gsdk_4.4/platform/bootloader/parser/gbl/btl_gbl_format.h)
- [Silicon Labs OTA documentation](https://docs.silabs.com/mcu-bootloader/latest/using-gecko-bootloader-with-bluetooth-apps/03-bluetooth-ota-upgrade)

Firmware files, credentials and raw captures must stay outside the repository.
