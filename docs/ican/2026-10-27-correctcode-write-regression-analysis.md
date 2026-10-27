# iCan: did our `correctCode` write stall a live sensor? (2026-10-27 trace)

Branch: `ican/correctcode-activation-window`. Source trace: `juggluco-trace-20261027-111415.log`
(JugglucoNG 1.2.6-Alpha-phone, Android 14, ASUS AI2401). Sensor: iCGM-t3,
`87600CE200070000` / auth `7600CE22404A`, session start 2026-10-20 08:27:26 +03.

Status: **hypothesis, not proof**. The trace cannot separate "sensor stopped on its own" from
"our parameter write stopped it". It does narrow the window to about two seconds.

---

## 1. What the trace proves

1. Transport, auth and history are healthy. `F3`→`F4` auth completes, MTU 247, services
   discovered, `F1` history request served in full: 32 batches, **3171 records**, RACP
   `06 00 F1 01` success.
2. `F6` sensor-info read **before** any write: `1C F6 01 FF FF` → code state `0xFFFF`
   ("never provisioned"). History worked anyway, at an offset of 3 in a 6.6-day-old session.
   That is experiment 1 from `docs/ican-cgm-protocol.md` §6 and it comes out **"the sensor does
   not gate history on the code"**. The premise that motivated auto-provisioning is not
   supported by this sensor.
3. We then wrote the table: three `F2 10` chunks, each acked `1C F2 01`, at 11:05:36–11:05:37,
   `CorrectCode write complete: 3/3 chunks acked`. `F6` on the next connection:
   `1C F6 01 90 01` → `0x0190` (400). The write landed.
4. After that, **no more data at all**. The `0x2AA9` counter read `9516` at 11:05:34,
   11:06:11 and 11:13:51 — 8 min 17 s with no advance, while the sensor normally produces a
   record every 3 min. Zero `2AA7` live measurement notifications (all 45 `2AA7` notifies in the
   trace are `53 4E …` history batches). Three sessions, all ending in GATT `status=8`
   (supervision timeout), then `LossOfSensorAlarm`.

## 2. The 722-minute "drift" is a false alarm (separate bug)

`Distrusting session timeline: start=1792474046000 seq=9516 implies 1793045006000 …
(drift=722min)` fires because `sessionTimelineMatchesSequenceCounter()`
(`ICanHealthBleManager.kt:3827-3861`) assumes the counter ticks **from session start**.

It does not. The first history batch came back with `base=3`, i.e. the sensor has records at
sequence 3, so the counter starts at (approximately) the **first record**, not at the session-start
write. The gap between the `0x2AAA` session start (10-20 08:27:26) and the first stored record
(10-20 20:35:34) is 12 h 08 m ≈ 722 min. The drift check is therefore measuring the activation →
first-reading delay, which on this sensor is permanent, and it fires on every reading.

Consequence: the driver falls back to "anchor off the live sequence", and the one-shot
"timezone repair" then **stamps the newest history record with the moment the status was read**
(`Covered edge seq=9516 time=1793088334127`). When the sensor is healthy that is harmless; when it
has stopped, the graph shows a fresh-looking value that may be hours old. In this trace the last
value (3.9 mmol/L) is displayed as "now" purely because of that anchor.

## 3. What the written table actually contained

Decrypting the three chunks from the trace (AES-128-ECB, key from
`ICanHealthConstants.DEFAULT_CORRECT_CODE_AES_KEY_ASCII`, challenge `8B C0 AE 8E` from `06 F3 01 …`)
and undoing the aux/XOR layer gives a well-formed table:

```
x1..x15 (raw/1000):
0.015 32.0 25.0 1.0 0.8 0.3 40.0 0.5 0.5 1.2 13.0 1.4 0.4 3.5 15.0
```

`x13 = 0.40` is the box code derived from the 13-char serial `87600CE22404A` (`"40"`), as intended.
No stray non-zero bytes outside the value slots; no uint16 wrap. It is not the generic default table
(it differs in x6, x8, x9, x12), so it passed the `isGenericTable` guard with real-looking values.

So the payload was sane. If the write broke the sensor, it was the **act** of writing parameters
into a running sensor, not corrupt bytes.

## 4. Where our path departs from the vendor's

| Vendor (`j2.java`, notes §1) | Our driver |
|---|---|
| writes `correctCode` only at activation, when `launcherState == 0` and code state is `0xFFFF` | writes on `F6 == 0xFFFF` when `startCommandIssuedThisConnection \|\| !shouldAttemptStandaloneStart()` (`ICanHealthBleManager.kt:2409-2413`) — by construction the second branch means "the sensor is **already running**" |
| `F7` (commit / initParameter) before/around `F2` chunks | **no `F7` anywhere in the driver**; only `F2` |
| write happens before/at session start, never during normal sync (iOS capture has no `F2/F7` at all) | write fires 3 s after sensor-info on any connection, including 7 days into a session |
| — | the chain is **not gated on history backfill finishing**: in the trace chunk 1 goes out at 11:05:36 while `2AA7` history batches are still streaming in, chunk 3 lands after `RACP transfer complete` |
| `launcherState` gate | `launcherState` is not consulted by `ICanHealthProvisioningPolicy.eligibility()` (marker + serial only) |

Also: the write path has been live since it was introduced. `CORRECT_CODE_WRITE_DRY_RUN = false`
in the initial commit `01c2fac26` (2026-10-22); it was never a dry-run default. That commit's
validation note says "live write (3/3 chunks acked, measurements unaffected) on a **provisioned**
iCGM-t3 sensor" — i.e. the one device test wrote to a sensor that already had a code, which is the
case our own eligibility check now refuses. A write into a **fresh, already-running** sensor had not
been exercised on hardware before this trace.

## 5. Hypotheses and what would settle them

| # | Hypothesis | For | Against | Discriminator |
|---|---|---|---|---|
| H1 | Sensor stopped on its own (fell off, electrode failure, end of life) | counter frozen; drift already present; no live-lane traffic ever seen on t3 | sensor produced a record seconds before our write; 20-day runtime was at day 7 | read `0x2AA9` now: still `9516` ⇒ stalled/dead; advanced ⇒ it recovered |
| H2 | Our `F2` write (uncommitted, mid-session, interleaved with RACP) wedged the measurement session | only intervention in the window; vendor never writes here; `F6` changed; sensor silent 2 s later | one earlier live write on a provisioned sensor "did not affect measurements"; calibration data should not stop sampling | the counter is permanently frozen after a write on a fresh sensor, and a second fresh sensor behaves the same |
| H3 | Sensor is fine, app can no longer see new data (notification/разрыв) | reconnect loop, `status=8` timeouts | counter itself (sensor-side state) does not advance | counter advancing while we see no notify ⇒ H3 |

H1 and H2 are both consistent with everything above. Nothing in this trace lets us choose.

## 6. Two independent app-side defects worth fixing regardless

1. **False "session timeline" drift** (§2). The counter is not session-start-relative; comparing it
   against `sessionStartEpochMs` guarantees a permanent warning and a permanent fallback to
   live-sequence anchoring on any sensor with an activation→first-reading delay. It also triggers
   the destructive repair (5275 stored readings dropped and re-imported here).
2. **Skip-history trap.** After the first pull, every reconnect logs
   `Skipping glucose-history read; persisted/local tail already covers current end index
   (tailSeq=9516 current=9516)` (`ICanHealthBleManager.kt:2824`). When the counter is frozen this is
   self-sustaining: no history read ⇒ no new records ⇒ the tail still "covers" the counter. A stale
   counter should force a tail re-read (counter or counter − 3) after N failed reconnects.

## 7. Next steps (no product code yet)

1. Cheap, no build: capture `0x2AA9` / `F6` / one tail history request today, hours after the
   incident. Frozen counter ⇒ H1/H2 (sensor dead or wedged); advanced ⇒ H3.
2. Ask what happened physically at ~11:14 (sensor removed? re-seated? phone away?).
3. If we decide to harden the write path: gate on `launcherState == 0`, send `F7`, never interleave
   with an active RACP transfer, and default `CORRECT_CODE_WRITE_DRY_RUN` back to `true` until a
   fresh-sensor write has been validated on hardware.

## 8. Update: the sensor recovered

Reported after the analysis: the sensor started working again on its own (no driver change between
the trace and the recovery). That removes the "permanently dead sensor" reading of H1 and weakens
the "write bricked it" reading of H2. What is left:

| Reading | Meaning | Test |
|---|---|---|
| H1-transient | sampling paused for a while (placement, electrode, firmware pause) and resumed | history contains a **gap** over the blackout window |
| H2-transient | the uncommitted `F2` write put the measurement session on hold until the sensor restarted/rolled over | same gap; ordering vs. a repeat on another fresh sensor decides |
| H3 | the sensor recorded the whole time, our driver simply never fetched it (`Skipping glucose-history read … tailSeq == current`, no live notify) | history is **continuous** across 11:05–recovery |

H3 is now the cheapest to rule in or out: look at the graph/DB for the window 11:05 → recovery
(`0x2AA9` was still `9516` at 11:13:51; a healthy sensor should show records there).

If H3 holds, the frozen counter was not "the sensor refusing to measure" but the driver's own
stale-tail trap: no history read because the tail equals the counter, no live notification on
iCGM-t3, and no timer forcing a re-read. That would make the correctCode write an innocent
bystander and move the priority to §6.2.

