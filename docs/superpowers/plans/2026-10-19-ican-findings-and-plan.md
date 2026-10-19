# iCan (Sinocare) — consolidated findings and plan

Date: 2026-10-19. Supersedes and folds in:
`docs/superpowers/plans/2026-09-07-ican-calibration-audit.md` (2026-09-07, updated 2026-09-23) and
`docs/superpowers/plans/2026-10-19-ican-verification-findings.md` (2026-10-19) — both deleted,
content carried forward here so there is one place to read, not three.

Primary external source, now copied into the repo so it survives:
[`docs/ican/ICAN-NOTES_2026-10-18-correctcode-investigation.md`](../../ican/ICAN-NOTES_2026-10-18-correctcode-investigation.md)
(412 lines, written on another machine, branch `t3/ican-history-fetch`, PR #635). Treat it as the
primary source for the `correctCode`/history-gating mechanics (§1 below) — this document
summarizes and corrects it, it does not replace reading it for the decompiled Java snippets.

---

## 0. Where this stands, in one paragraph

**Updated 2026-10-19, second pass** (after reading GitHub issue #676 and PRs #635/#677 directly —
this correct a mistake this document itself made a few hours earlier, see the note at the end of
this section). The original premise (Sept 7) was "the glucose formula is correct, JugglucoNG's
decode pipeline breaks it somewhere." That premise is **wrong** in its emphasis. The dominant
real-world symptom — "iCan reads wrong/won't backfill history" — is **not** a JNG decode bug at
all: a sensor the official app never touched is missing a server-fetched 25-coefficient blob
(`correctCode`) that the vendor SDK requires before it will serve RACP history, and JNG never
fetches or writes it (confirmed on hardware, A/B, two real sensors, §1). A second, real JNG defect,
now **fixed but not yet merged** (PR #635, commit `d6fe2201d`): the session-start wall clock
(`0x2AAA`) was misread as a BT-spec timezone offset when byte 7 is actually a vendor constant that
means nothing — the fix resolves the wall clock in **the reading device's own zone** instead (§2.1).
A third, newly surfaced defect, **open, unimplemented, and the most safety-relevant item in this
whole document**: on `iCGM-t3` sensors specifically, the live BLE notification's value field does
not carry glucose at all — it behaves as a bare "new record available" trigger, and a wrong-but-
plausible value (confirmed in-range on real data) could reach the live display with no warning
(§1.9, GitHub issue #676 follow-up 3). A fourth, also open: live notifications are decrypted with
AES/ECB when the vendor uses AES/CBC with the auth-challenge IV, corrupting two unused fields today
(§1.8, issue #676 follow-up 2) — currently harmless only because nothing reads those two fields yet.

**Do first:** §1.9 (iCGM-t3 live-value guard). Unlike F2–F5 below, which are dead code with no
live-path effect, this one is live and user-facing *today*: on a t3 sensor the corrupted field *is*
the live glucose reading, so a real user with that sensor model may be looking at a wrong-but-
in-range number right now. Needs a live t3 sensor to confirm before shipping a fix (the owner has
one). **Do second:** merge PR #635 — §2.1's fix is already written and tested; the remaining
blockers are §1.6 (the `planCalib` arity question) and the PR body being stale (§6). **Do third:**
§1.4 (F6=0xFFFF diagnostic warning) and §1.8 (ECB→CBC live decrypt) — both low-risk, well-specified,
no protocol change. **Do last, and only after a device test:** history-backfill policy (§1.7).

**Correction to this document's own first pass (same day):** the first version of this section and
§2.1 recommended "resolve the `0x2AAA` wall clock as GMT+8, unconditionally" as the fix target.
That recommendation was wrong, and would have been a regression if shipped: it was built from a
single data point (a sensor activated and read by the same +0300 phone, where only a fixed GMT+8
happened to match) without weighing the two data points that justify the fix PR #635 actually
shipped (device zone — correct for an iPhone at +05 and an Android at +08, both self-consistent
cases). Re-reading issue #676 and the PR #635 commit directly resolved this — see §2.1 for the
corrected account, including why both "device zone" and "fixed GMT+8" are each wrong in different,
narrower circumstances and there may be no fully general fix.

**Second correction, three days later (2026-10-22): §1.5's "do not attempt the correctCode write
path" is withdrawn.** It was accurate on 2026-10-19 (the chunker and server call looked VMP-native
and out of reach) and is not accurate now. GitHub issue #679, opened the same day this document
was first written, tracks an active, fast-moving effort that has since: reproduced the vendor's
write framing byte-for-byte against a real capture (draft PR #680, 173 tests green, not yet
hardware-verified); and, as of 2026-10-22, reproduced the *server-side* `correctCode` fetch with a
plain HTTP client and no vendor SDK, hooks, or MITM, verified against both an already-activated
sensor's own logged table and two fresh unactivated serials. See §1.5 for the current state — it
no longer says "don't," it describes what exists and what's still missing. **This whole area moves
in hours, not days** (three conclusions in the issue thread were each withdrawn by a later comment
the same day they were posted) — treat §1.5 below as a snapshot taken 2026-10-22, read issue #679
directly for anything more recent, and do not quote specific credential/table values from there
into anywhere else; the issue itself deliberately withholds them and this document follows the
same discipline.

---

## 1. `correctCode` gates RACP history — proven on hardware

Source: `ICAN-NOTES_2026-10-18-correctcode-investigation.md` §1–§3, independently re-verified
2026-10-19 against a different sensor/phone (see §2.1 below for the zone correction, and the table
in §1.3 for what else was independently re-checked).

### 1.1 What `correctCode` is

A JSON blob with 25 float coefficients (`x1..x25`), fetched from the vendor's cloud
(`GET sino-deviceaccess/sdk/cgm-code-encrypt`), written into the sensor by the official app. On the
real captured device the write is **`F2` only, three chunks, no `F7`** — issue #679 corrects the
original two-command assumption (`F7` `initParameter` is a *separate*, SN-derived fallback path,
`writeInitParameter`, that a fresh real activation didn't exercise; `F2` `writeCorrectCode` is the
server-table path and what actually ran). **As of 2026-10-19, JugglucoNG never sent F6-write,
F7, or F2** — confirmed by searching the driver and by the live-capture analysis in §3: across 108
real connections to a provisioned sensor, the only opcodes ever written were `0x02`/`0x01` (CCCD),
`0xF1` (RACP), `0xF3` (auth prime), `0xF4` (auth token). **This is changing**: see §1.5 — a draft,
debug-gated implementation of the write path now exists (PR #680), off by default.

### 1.2 The tell-tale: opcode `F6` ("sensor info" / code-state marker)

```
TX h=0x0024: F6
RX h=0x0024: 1C F6 01 <lo> <hi>      ; code_state = (hi<<8)|lo
```

- `0xFFFF` → `correctCode` was never written → **RACP history is permanently refused**
  (`06 00 F1 0A` / `06 00 01 0A`) no matter how many reconnects. This is not a driver bug; the
  sensor itself refuses.
- Any other value → provisioned, history works. Observed values keep growing — treat **"non-FFFF"
  as the signal, never a fixed value list**: 500, 510, 530, 600 (Sept report), **570** (§1.3,
  2026-10-19, an officially-activated sensor), **590** (2026-10-20, a CE-app activation captured
  end-to-end, §1.5). Any code that gates on an enumerated set of "known good" values instead of
  `!= 0xFFFF` will eventually treat a real provisioned sensor as unprovisioned.

### 1.3 Independent re-verification (2026-10-19, different sensor/phone)

Against `juggluco-trace-20261008-175444.log` (RU build, sensor iCGM-t3 `83D0054400070000`, Samsung
SM-S938B, phone at +0300 — a different sensor and a different phone than the original report):

| Claim | Re-check | Result |
|---|---|---|
| Branch/PR/commit exist | `gh pr view 635`, `git log` | confirmed, `d8d2d610d` |
| `SinoCgmService` = exactly 3 CGM calls | `grep` the Dart sources in `ce-20261018.zip` | confirmed: `user/get-user-without-login`, `country/setting/detail`, `user/active` |
| `rebind`/`unbind` are account ops, not sensor ops | enumerated the rest of the endpoints | confirmed, all `sino-ican/v1/user/*` |
| i18n binding-constraint string | `ce-lang-en.json` | confirmed verbatim |
| `libsinoCgmSdk.so` → `AlgManager` JNI | `strings` on the extracted `.so` | confirmed: `planCalibJNI`/`startCalibJNI` |
| `fix_ican_dynsym.py` 9-field patch | read the script | confirmed |
| Dart snapshot hash | `blutter_ce_run4.log` | confirmed `97ff04a7…` |
| F6=0xFFFF never warned in JNG | grepped the driver | confirmed, no such log line exists |
| New F6 sample **570** | this trace | not in the original 500/510/530/600 list; sensor was officially-activated (`recovered=…`) → corroborates "non-FFFF = provisioned" independently |
| **GMT+8 vs device-zone** | arithmetic below | **corrects** the original report's open question — see §2.1 |
| `AlgNamespace::planCalib` arity | mangled symbol in `libsinoCgmSdk.so` | **6 floats** in the CE bridge vs **5 parameters** in the `d8d2d610d` Kotlin port — open, see §1.6 |

Not re-checked here (paths were on the other machine, decompiled Java not available locally):
the `j2.java`/`f1.java`/`BleDataUtilKt.java` jadx line citations in the primary source. Treat those
specific line numbers as reported-but-not-independently-verified; the mechanics they describe
(§1.1–1.2 above) **are** independently corroborated by the live traces.

### 1.4 Action: warn when F6 = 0xFFFF (not yet implemented) — tracked as GitHub issue #676, follow-up 1

Pure diagnostics, no protocol change. Driver currently has `isSensorInfoResponse`
(`ICanHealthParser.kt:570-575`, per issue #676's line numbers) but never inspects the F6 value, only
logs the raw hex (`ICanHealthBleManager.kt:2302-2311`). Add `parseSensorInfoCodeState(data): Int?`
next to `isSensorInfoResponse`; payload is `1C F6 01 lo hi`, code state = `lo | (hi shl 8)`. When
it's `0xFFFF`, log
`"sensor has no correct code (F6=0xFFFF); it will refuse history reads until it is provisioned —
start sensors with the vendor app first"`. Do **not** attempt to write the code — the chunker and
key are server-side and VMP-native (§1.5). Tests per issue #676: `0xFFFF` flagged; `500/510/530/600`
not flagged; 3-byte and 5-byte payloads and short data handled (`null`/no-op, not a crash).

### 1.5 Provisioning a sensor without the vendor app — active work, not "do not attempt" (GitHub issue #679)

**This section said "do not attempt" as of 2026-10-19. That's withdrawn as of 2026-10-22 — read
this whole section, not just the heading, before acting on either the old or the new framing.**

**Still true, unaffected by any of this:** "connect the vendor app once to repair an
already-JNG-activated sensor" does not work and is not what's being built. The vendor app gates on
its own cloud account binding and only ever unbinds/terminates a device bound to the *current
account* — it does not adopt a sensor it did not itself activate, and per the owner, attempting
this could terminate the wrong sensor (whatever the account has bound in the cloud), not the one
physically at hand. **Nobody is trying to make the vendor app cooperate with a JNG-started sensor,
and nobody should.**

**What changed: issue #679 asks a different question** — not "how do we repair a sensor JNG already
started," but "can JNG itself fetch and write the `correctCode` table as part of its *own*
activation, so the vendor app is never involved at all." As of 2026-10-22 the answer looks like
yes, in two independently-solved halves:

- **Write framing (draft PR #680, `wip(ican): write the correctCode table from our own activation
  (debug-gated)`, 1891/−1 lines, 173 tests, not yet draft-cleared or hardware-run):** reproduces the
  vendor's exact `F2`-chunk wire format against a real captured activation, byte-for-byte. Key
  finding along the way: the key material is the **whole 7-byte `F3` notification** (`06 F3 01` +
  the 4-byte challenge), not just the 4-byte challenge as first assumed — an early version of the
  prototype had this wrong. Gated behind `ICanHealthExperimental.ENABLE_CORRECT_CODE_WRITE = false`
  (pinned by a test so it can't silently flip), refuses outright unless `F6 == 0xFFFF`, unauthenticated,
  or the sensor is not idle, and a given serial can be attempted **at most once, ever** (persisted
  across reconnects/app updates). No live sensor has run this path yet.
- **Server fetch (no PR yet, mechanism described in the 2026-10-22 issue comment, not merged
  anywhere):** the per-serial `correctCode` table can be fetched from the vendor's cloud with a
  plain HTTPS GET and local-only crypto (RSA public-key operation to recover an AES key, then
  AES-CBC to decrypt the table) — **no vendor SDK, no Frida hook, no TLS interception.** Verified
  against both an already-activated sensor (fetched table matched its own locally-decrypted SDK
  log) and two fresh, not-yet-activated serials. A working Kotlin translation reportedly exists but
  is not yet wired into #680 or committed anywhere in this repo.

**What this document deliberately does not reproduce, matching the issue's own discipline:** the
endpoint's signing formula structure is described above because the issue itself states it
publicly, but the actual tenant `sdkAppId`/`sdkAppSecret` values, any recovered `correctCode`
table, raw ATT/HCI captures, and serial numbers are explicitly kept out of the public issue and are
not reproduced here either. If you need them for development, ask in the issue, don't guess.

**Status for anyone picking this up:** nothing from issue #679 is merged. Both halves exist as
artifacts (a draft PR and a described-but-uncommitted fetch mechanism) with zero hardware
verification of the full write. Read issue #679 end-to-end before touching this — the thread
corrects itself multiple times per day (an "anti-tamper boundary" conclusion and a "logs need a
3072-bit RSA key" conclusion were each withdrawn hours after being posted) and this document cannot
keep up with that cadence. Do not start independent work on the same problem without checking the
issue first — it is very likely already in progress there.

### 1.6 Open: `planCalib` arity mismatch (blocks "planCalib matches" claim in PR #635)

PR #635's body claims `planCalib` matches the vendor binary unchanged. The CE-build bridge symbol
(`libsinoCgmSdk.so`, app version 01.17.00.19) demangles to **six** float parameters; the Kotlin
port in `d8d2d610d` (`ICanHealthCeCalibration.planCalib`) takes **five**. `startCalib` matches
exactly (both sides), so this isn't a wholesale mismatch — just `planCalib`. Possible explanation:
the port was checked against a different vendor version (01.18.00.19) where the signature may
differ. **Before merging PR #635 or treating `planCalib` as verified, resolve this**: either find
the 01.18.00.19 bridge symbol and compare, or get the CE build's actual float-by-float semantics
for the 6th parameter. Until then `planCalib`/`lineFit` are "probably fine, not confirmed," not
"confirmed," regardless of what the PR body currently says.

**Re-checked 2026-10-19, still open:** PR #635's body text (re-read directly via `gh pr view 635`)
has not been edited since `d8d2d610d` — it describes only the `startCalib` fix and does not mention
the second commit (`d6fe2201d`, the `0x2AAA` fix in §2.1) at all, nor this arity question. The
automated Greptile review attached to the PR is also stale for the same reason: its "Last reviewed
commit" is `d8d2d610d`, not `d6fe2201d`, so its approval does not cover the timezone fix either.
Neither the PR body nor the review has caught up with the PR's actual current diff.

### 1.7 History backfill policy — needs a device test before any code change (issue #676, follow-up 5)

Separate from `correctCode`: even on a *provisioned* sensor, JNG's history catch-up only re-reads
from `max(lastHandledLiveSequence, persistedTail) + interval` (`ICanHealthHistoryPolicy`) — it never
re-reads a hole that sits behind an already-advanced tail. **This question now has a direct answer,
not just a test plan:** issue #676 reports the official app *does* re-read from its own last
persisted offset and the sensor *does* serve the hole — captured directly: `t=21641`, counter
(`2AA9`) read 42, request offset 30, and the returned SN batch contained records 30, 33, 36, 39, 42.
So the sensor supports it; this is a **driver policy decision**, not an open hardware question
anymore — whether to spend the extra BLE time per connection re-reading holes is a deliberate
trade-off call, not a prerequisite test.

### 1.8 Live notifications are decrypted with ECB; the vendor uses CBC/PKCS7 with the auth IV — open, low current impact (issue #676, follow-up 2)

`ICanHealthParser.parseGlucose` (`ICanHealthParser.kt:166-171` per issue #676) decrypts the 16-byte
live-notification payload with `ICanHealthCrypto.decryptBlock` — AES/ECB/NoPadding. The vendor
decrypts the *same ciphertext* as **CBC with `buildAuthIv(challenge)`** — the identical IV
construction JNG's own SN-history path already uses
(`ICanHealthParser.kt:383-384`). Evidence: CBC decryption gives a self-consistent record
(`[0] == 16 − padLen`, `[13..15] == 03 03 03`) across 11/11 frames in the iPhone trace, each with a
*different* challenge (so this isn't a coincidence of one key). It also resolves a previously
unexplained oddity: the logged `flags` byte (`0x24`/`0xC7`/`0x71`/`0xB0` across different sessions)
is always exactly `0x80 ⊕ challenge[0]` — a direct consequence of decrypting with the wrong IV.

Since `buildAuthIv` only sets bytes 1, 6, 10, 15 of the 16-byte block (challenge bytes 0–3 at those
positions, zero elsewhere), ECB-vs-CBC decryption only disagrees at those four byte offsets. Mapped
onto `ICanHealthGlucoseReading`'s fields: **glucose `[2:4]`, sequence `[4:6]`, the data-state nibble
`[3]`, and temperature `[11:13]` have always been correct** (none of those positions fall on 1, 6,
10, or 15); **`statusByte` `[1]` and `currentValue` `[9:11]`** (which includes byte 10) **have
always been garbage.** Both are currently unused — `statusByte` has no consumer, and
`resolveRawLaneValue()` (the only `currentValue` reader) returns `NaN` regardless — so nothing
user-visible is broken today, same shape as F5 in §4 (in fact this is likely F5's actual root
cause: `decodeCurrentValue`'s bogus-looking output was never going to make sense, because its input
bytes were never decrypted correctly in the first place).

**Fix:** use `ICanHealthCrypto.decryptCbcPkcs7(encrypted, aesKey, buildAuthIv(authChallenge))` with
a conservative fallback to the current ECB path when there's no challenge or the PKCS7 pad is
invalid (don't drop a reading over this). **Test:** a frame with a known (challenge, ciphertext)
pair must decrypt to the `0D 80 …` record shape; a bad pad must fall back, not discard the reading.

**Status 2026-10-22: implemented as draft PR #683** (first of its two commits, described as
"independently merge-worthy" by its own author). `parseGlucoseNotification` now requires the
4-byte challenge (CBC has no self-contained IV) and decrypts with `decryptCbcPkcs7`; byte 1 turned
out to be the launcher state, not a free-form status byte, and bytes 9–12 are zero fill (no live
current/temperature at all — a stronger finding than this document's original "garbage" framing).
158 tests green (capture-backed vectors from both an iCGM-t6 log and the iPhone trace in §3). Not
yet run against real hardware. PR is draft, not merged.

### 1.9 On `iCGM-t3`, the live packet's value field is not glucose at all — OPEN, safety-relevant (issue #676, follow-up 3)

On `iCGM-t6` the live value field exactly equals the history record at the same sequence (verified:
seq 837 → live 545 = history 5.45 mmol/L). **On `iCGM-t3` it does not:** seq 18 → live 1755 vs.
history 5.97 mmol/L; seq 45 → 3720 vs. 4.33; seq 54 → 4031 vs. 4.19; seq 102 → 1013 vs. 3.92 — no
monotone or affine relation, not a CRC-16 of the record (8 standard variants tested), not
temperature, not the history "current" field, and the value jumps 1013→3134 between two records
whose glucose barely moved. It behaves like a one-shot nonce/trigger ("a new record is available"),
consistent with the official app requesting history ~5 s after receiving each one, rather than
trusting the live field.

**Risk:** a `t3` sensor's live lane would publish a wrong-but-*plausible* value — the cited example,
17.55 mmol/L = 316 mg/dL, sits **inside** `MIN_VALID_GLUCOSE_MGDL`/`MAX_VALID_GLUCOSE_MGDL` — with
no warning, no out-of-range rejection, nothing to flag it as wrong. This is the one item in this
document where a live, in-production, user-visible number could be wrong *right now* if any current
JugglucoNG user has a t3-variant sensor.

**Fix (needs a live t3 sensor to confirm first — the owner has one):** when the sensor model
(`vendorModelName`, captured at `ICanHealthBleManager.kt:1495`) is an `iCGM-t3` variant, treat the
live packet as a trigger only — do not emit a live glucose reading from its value field; let the
history path (RACP) supply glucose for that sequence instead, with whatever latency that implies.
Do not guess the fix without the confirmation step; this document is not asking anyone to ship this
blind.

**Status 2026-10-22: implemented as draft PR #683 (second commit) — and better than the fix
sketched above.** Rather than hardcoding on `vendorModelName`, it adds
`ICanHealthLiveSemantics { UNKNOWN, VERIFIED_GLUCOSE, TRIGGER_ONLY }`: a per-sensor classifier that
compares the live value against the history record at the same sequence as both arrive (within 1
raw unit = match), needs 3 decisive agreements or disagreements to commit to a verdict (sticky
through 1–2 contrary observations), and persists the running counts per sensor serial — so it
works on any sensor variant without needing to know in advance which models are affected, and
self-corrects if a model's behavior turns out to vary. `TRIGGER_ONLY` suppresses the live-path
publish and relies on RACP for that sequence instead (nothing published if history never arrives —
no fabricated carry-over value); `UNKNOWN` currently still publishes, gated by a flag
(`ICanHealthExperimental.SUPPRESS_UNVERIFIED_LIVE_GLUCOSE`, default off) to switch it to
`TRIGGER_ONLY`-like behavior once there's enough field evidence. 158 tests green. **Not run against
real hardware** — the author's own PR notes this explicitly, and separately asks for more
genuine live/history vector pairs from any iCan family to strengthen the capture-backed test
vectors. PR is draft, not merged.

---

## 2. Session-start time (`0x2AAA`) — two separate, unrelated bugs

Do not conflate these. One is about which **zone** to interpret the wall clock in (new, high
priority, read-side). The other is about **byte layout on write** (old, from Sept, low priority,
write-side). Fixing one does not touch the other.

### 2.1 Read-side: byte 7 of `0x2AAA` is a vendor constant, not a real offset — fixed, not yet merged

`ICanHealthParser.kt:233` (`parseSessionStartTime`) read byte 7 of the 9-byte `0x2AAA` value as a
signed BT-spec Time Zone field (sint8, ×15 min, `-128`=unknown — constant `TIME_ZONE_UNKNOWN` at
`ICanHealthParser.kt:112`); `toEpochMillis()` (`ICanHealthParser.kt:66`) then applied that offset.

The vendor writer does not work this way: decompiled `j2.java:1115` always writes byte 7/8 as the
hard-coded constants `20, 0` (`20` = UTC+5:00 by BT-spec coincidence) regardless of the activating
phone's real zone. Reading `0x14` as a genuine +5:00 offset puts the session start wrong by
`(real zone − 5h)` — e.g. a +178 min drift on an +08 sensor, which permanently trips
`Distrusting session timeline` on every single reading.

**What's actually true about the correct interpretation — read this before touching the code
again, because this document itself got it wrong once (see §0):**

Two real captures justify the shipped fix:

| Capture | Device zone | `2AA9`-counter-implied zone | Device zone matches? |
|---|---|---|---|
| iPhone, 2026-09-27 (PacketLogger) | +05 | +05 | yes |
| Android, 2026-08-19 | +08 | +08 | yes |

PR #635 commit `d6fe2201d` (title: "ignore the vendor's hard-coded 0x2AAA timezone") ships exactly
this: `parseSessionStartTime` now also rejects `0x14` (named `TIME_ZONE_VENDOR_CONSTANT`) the same
way it already rejected the BT-spec `-128` sentinel, so `timezoneOffset15Min` becomes `null` and
`toEpochMillis()` falls through to its existing "unknown" branch — which resolves the wall clock in
**the device's own current zone** (`TimeZone.getDefault()`), not GMT+8. On the two captures above
this gives the right answer both times, because in both the phone reading the sensor *is* the phone
that activated it (same zone both ways by construction).

**But there is a third, independent data point this document found that device-zone does not
explain**, and PR #635's own commit message doesn't mention it (it wasn't one of the two captures
used to justify the fix): `juggluco-trace-20261008-175444.log`, Samsung SM-S938B at **+0300**,
sensor iCGM-t3 activated by the official app:

```
WIRE rx read 2AAA EA 07 09 1B 02 08 2D 14 00
W/ICanHealth Distrusting session timeline: start=1790456925000 seq=17070
    implies 1791481125000 but the counter was read at 1791470432297 (drift=178min)
```

| | |
|---|---|
| `2AAA` wall clock (raw digits, no zone applied yet) | 2026-09-27 02:08:45 |
| counter-implied UTC start (`2AA9` offset 17070, read at a known UTC instant) | 2026-09-26 18:10:32Z |
| zone that makes the wall clock agree with the counter | **+7.97 h ≈ GMT+8** |
| phone's actual zone (the one running JugglucoNG during this capture) | **+0300** |

Device zone (+0300) does **not** match here — only a fixed +8 does, which is ~5 hours away from
what the shipped fix would compute. **The likely explanation, not confirmed:** this sensor's wall
clock was baked in once, at whatever moment the *official app* first activated it — which may have
been a *different phone, in a different zone*, than the Samsung phone reading it during this
capture. If so, "device zone" is correct exactly when the reading phone and the original activating
phone share a zone (true for both captures that justified the shipped fix, by construction — in
both cases the capturing phone *was* the activating phone) and wrong otherwise (the Samsung case,
where the sensor had evidently been activated elsewhere). **Neither "device zone" nor "fixed GMT+8"
is a fully general fix** — the true rule needs the activating phone's zone, which the sensor's own
bytes do not carry, so no fix recoverable from `0x2AAA` alone can handle sensors that changed
zones between activation and use. Shipping "device zone" (what PR #635 already does) is still the
right default: it is correct for the common case (JNG activates its own sensor) and only wrong for
the narrower one (sensor activated elsewhere, in a different zone, before JNG ever saw it) — whereas
"fixed GMT+8" (this document's retracted first-pass recommendation) would have been wrong for the
*common* case to fix the rarer one. Do not revisit this without new data; if the residual case
matters enough to address, it needs a way to record "what zone was this phone in when it activated
this specific sensor" and nothing in the current driver does that.

**Status:** fixed on `t3/ican-history-fetch` (`d6fe2201d`), PR #635, **not yet merged to `main`**.
Four new tests added there, full `icanhealth.*` suite green (139 tests, confirmed 2026-10-19). The
residual edge case above is not covered by any test and not claimed as fixed by the PR — this
document is the first place it's written down; consider filing it as a follow-up issue if it's
worth tracking deliberately rather than leaving it only in this doc.

**Why this was high priority, not cosmetic:** the drift triggers `Distrusting session timeline` on
*every* reading for any sensor whose activating zone differs from GMT+5 (not just non-+8 users —
correct this from the first-pass wording), which feeds `observedEndedStatusEndMs()`,
`hasCompleteEndedStatusHistory()`, the "capped" session-end window logic, and the one-time
`HISTORY_TIMEZONE_REPAIR_GENERATION` migration.

**Separate, still relevant:** worktree `.worktrees/ican-anchor-trace`, branch
`fix/ican-anchor-trace` (base `origin/main` at `1e2a1f2b5` — rebase before using), has temporary
trace points (`ICanAnchorTrace.kt`, events `ev=anchor/resolve/live/edgeWrite/edgeDrop/endedCap/
mirror`, logged via `Log.e` under tag `ICanTrace` because R8 strips `.i`/`.d`/`.v`/`.w` in release
per `proguard-rules.log:1-7`) built as `:Common:assembleMobileReleasedub` and installed over
`tk.glucodata.ng.dub`. This could still be useful for the residual edge case above (capturing the
activating phone's zone alongside the wall clock on a device that changes zones), but is not needed
for the device-zone fix itself, which is already shipped. **Remove this instrumentation before
merging anything** — it's explicitly temporary.

### 2.2 Write-side: `buildSessionStartWrite` packs TZ/DST as one int16 — MEDIUM, still open, unchanged since Sept

`ICanHealthParser.kt:272` (`buildSessionStartWrite`). The reader (`parseSessionStartTime`) was
fixed at some point to read byte 7 (Time Zone, sint8) and byte 8 (DST Offset, uint8) as two
**separate** spec fields — this is itself covered by a test
(`parseSessionStartTime_readingBytesAsInt16WouldFoldDstIntoTheOffset`). The writer never got the
matching fix: it still packs the combined offset into one little-endian int16 across both bytes.
For a positive offset this happens to produce `[tz, 0]` (correct by luck); for a negative offset it
produces `[tz, 0xFF]`, where `0xFF` happens to equal the DST "unknown" sentinel (also correct by
luck, for unrelated reasons).

**Re-verified unchanged on `main` 2026-09-23**: code is byte-for-byte the same as when first
found. A round-trip test (`ICanHealthParserExtraTests.sessionStartRoundTripsNonZeroOffsets`,
`GMT+02:00`/`GMT-05:00`) was added since but does not exercise the bug: both zone IDs are
Java fixed-offset zones (`TimeZone.getTimeZone("GMT+02:00").useDaylightTime() == false`,
verified directly) and so never produce a real DST component regardless of date — the asymmetry
never gets exercised. **To actually test this, use a real IANA zone with DST on the test date**
(e.g. `America/New_York` in northern-hemisphere summer), not a `GMT±HH:mm` literal.

Lower priority than §2.1: this is a protocol-symmetry bug in what JNG *writes*, independent of the
GMT+8 interpretation question (which is about *reading*, and about a field the vendor doesn't even
look at when reading — so fixing §2.2 does not help §2.1 and vice versa). Worth fixing for
correctness, not urgent.

---

## 3. What this session's own live-capture analysis (2026-10-19) found

Source: `~/Downloads/ican_1.pklg` … `ican_4.pklg` (Apple PacketLogger / HCI, iPhone 14,5, iOS 26.6,
captured 2026-09-26). Flagged as an unanalyzed artifact by the 2026-10-19 verification doc; parsed
and analyzed in this session. Scratchpad scripts (`parse_pklg*.py`) are ephemeral — the numbers
below are the durable record.

**File relationship, important:** all 7 `.pklg` files in Downloads (`123.pklg`, `1234.pklg`,
`1737.pklg`, `ican_1..4.pklg`) start at the identical timestamp (18:52:24) — they are periodic
saves of **one continuous PacketLogger session**, not independent captures. `ican_4.pklg` is the
complete superset (ends latest, 02:21 next day). Don't sum counts across files — that double/
triple-counts the same events; this was caught and corrected mid-session (an earlier "435 connects,
201 timeouts" figure was wrong for this reason; the corrected numbers below are from `ican_4.pklg`
alone).

- `123.pklg`/`1234.pklg`/`1737.pklg` contain a **different** device (`94:a9:a8:47:89:0e`, ASCII
  single-letter opcodes `U/H/W/R/T/S/C/X` on a different characteristic layout) — not iCan, not
  investigated further.
- The iCan sensor's address is `bc:8d:7e:3c:b2:42`. Across the full session (`ican_4.pklg`, 108
  real connections over ~7.5 hours):
  - **Every opcode ever written to this sensor**: `0x02`/`0x01` (CCCD, 202+60), `0xF1` (RACP
    report-all, 106), `0xF3` (auth prime, 65), `0xF4` (auth token, 63). **Zero F6, F7, F2** —
    consistent with §1: this sensor was already provisioned before the capture started, so no
    `correctCode` write was ever needed during the whole session. (An earlier pass flagged 88
    apparent F6/F7/F2 hits; all were false positives — `ExchMtuResp` values `f7 00` = MTU 247,
    not a vendor opcode. Confirmed zero after filtering to real opcode fields.)
  - RACP **succeeds** on this sensor: `WriteReq h=0x0021 f1 01 02` → `RX h=0x0021 06 00 f1 01`
    (success, not the `0A` refusal code), preceded by a real `"SN"`-prefixed glucose-history batch
    notification. Consistent with "non-FFFF F6 ⇒ history works."
  - Read right after every successful auth: `0x2AAA` → `ea 07 09 1b 02 08 2d 14 00`, byte 7 =
    `0x14` — same vendor constant as §2.1, now confirmed on a **third**, independent capture (this
    one from the real iOS official app, not a decompile or an Android trace).
  - Disconnect reasons: 100× `reason=8` (Connection Timeout), 8× `reason=62` (Connection Failed to
    be Established). **Zero clean, app-initiated disconnects.** This closes the oldest open
    question in the whole iCan investigation (from `docs/ican-official-app-reverse-engineering.md`
    and `ican-ios-connection-blocker-brainstorm.md`, 2026-06-11): the official iOS app does **not**
    hold the BLE link any better than JugglucoNG does, and has no special clean-disconnect trick —
    it just reconnects aggressively after the same kind of timeouts we see.
  - **Caveat on that last point** (owner's objection, correctly raised): the reconnect-gap
    histogram is not uniform, and a naive "same as us" claim overstates it. Gaps between a
    disconnect and the next connect, 107 samples: `<1s`: 9, `1–5s`: 42, `5–30s`: 29, `30–60s`: 9,
    `60–300s`: 14, `>300s`: 4 (max 1046s ≈ 17 min). The **dominant** pattern (42/107, plus the <1s
    and 5-30s buckets — roughly 80/107) is a reconnect fast enough that it cannot be "walked back
    into range" — that part supports "same timeout churn as us, not a range issue." But the
    **long tail** — 18/107 gaps over a minute, up to 17 minutes — is too long to be a protocol
    timeout artifact and is plausibly genuine signal loss (phone left in another room, etc.), i.e.
    the owner's hypothesis is right for that minority of the disconnects, just not for the bulk of
    them. Don't present this as fully one thing or the other.

---

## 4. JugglucoNG code-bug catalog (from the original Sept 7 audit), current status

These are concrete findings in JNG's own `icanhealth` driver, independent of the `correctCode`
story. Carried forward with fresh line numbers (`main` as of 2026-10-19) and updated status. Full
original reasoning (why each one is a bug, with before/after code) is not repeated here if
unchanged since Sept — see git blame / the deleted Sept doc's last state if the archaeology is ever
needed; nothing here depends on recovering that text.

| ID | What | Status (2026-10-19) |
|---|---|---|
| F1 | `ICanHealthDriver.kt:53` `supportsSensorCalibration() = false` | **Unchanged, intentional.** Owner confirmed iCan is factory-calibrated and manual calibration is deliberately off. Do not flip this flag. Not connected to the `correctCode` story — that gates *history*, not manual calibration UI. |
| F2 | `ICanHealthCeCalibration` dead code — `calibrateSensor()` returns before reaching it (because of F1) | **Still true.** Zero runtime effect regardless of what's inside it. |
| F3 | `startCalib` field-mismatch bug in `ICanHealthCeCalibration.kt` | **Fixed, pending merge.** PR #635 (`d8d2d610d`, branch `t3/ican-history-fetch`) rewrote `startCalib` to match the real vendor `libAlg.so` branch-for-branch. Tests green (8/8, confirmed by independent re-run 2026-10-19: `BUILD SUCCESSFUL`, 136 tests across 15 `icanhealth` classes, 0 failures). Still has zero runtime effect (F1/F2) — fixed for correctness, not because it's reachable. |
| F4 | `startCalib` ramp-factor formula divergence | **Fixed in the same PR #635 commit as F3** — see above. |
| F5 | `decodeCurrentValue` (`ICanHealthParser.kt:760`) — `(byte xor 0x88)` then a literal `"25"` string prefix before parsing as float; not grounded in anything, produces a 7-digit "current" value where a real electrochemical current is single/double digits nA | **Still open, not touched by PR #635 — and now has a likely root cause, §1.8.** `currentValue` is read from bytes 9–11 of the *decrypted* measurement payload, and byte 10 is exactly one of the four bytes that ECB-vs-CBC decryption disagrees on (§1.8) — so `decodeCurrentValue`'s input was probably never decrypted correctly to begin with, which would explain why its output never looked like a real current. Fixing §1.8 first, then re-examining whether `decodeCurrentValue` still looks wrong on correctly-decrypted bytes, is a more useful order than patching F5 in isolation. Still unreachable from the live glucose value either way (glucoseMgdl comes from `[2:4]`, unaffected by the ECB/CBC bug) — zero live-path effect today. |
| F6 (Sept numbering) | write/read asymmetry in session-start TZ/DST byte packing | **Renamed here to §2.2** to avoid confusion with the BLE opcode `0xF6` used throughout §1. Still open, see §2.2 for current detail. |
| F7 (Sept numbering) | `ICanHealthConstants.kt:542,550` XOR-table doc says "16-byte," tables are actually 64 bytes (4×16); `ICanHealthParser.kt:746` `% (table.size - 2)` unconfirmed modulus | **Still open.** Comment fix is trivial; the modulus choice needs vendor confirmation or a live warm-up-window trace before touching the arithmetic. |
| F8 | test coverage of `icanhealth/*` | **Mostly resolved since Sept 23.** 15 test classes, 136 tests, 0 failures (confirmed 2026-10-19). `ICanHealthBleManager.kt` (4225 lines) is still the one large file with **zero** tests — the GATT state machine, activation sequence, and history backfill logic all live there untested. If anyone adds tests for the `correctCode`/F6-diagnostic work (§1.4) or the GMT+8 fix (§2.1), that file is where the gap actually matters. |
| F9 | Owner's original report: activating a sensor via JNG makes it unusable by the official app afterward | **Root cause understood; "nothing to fix on JNG's side" is now outdated — see §1.5.** The specific symptom (vendor app can't repair a JNG-started sensor) is permanent and unfixable, for the reason originally given: the vendor app's cloud/account model doesn't support adopting a foreign-activated sensor. But as of 2026-10-22 that's no longer the end of the story — issue #679/PR #680 aim to make JNG activate sensors *correctly provisioned* in the first place, so there is nothing to repair and no vendor app is ever needed. Still a draft, zero hardware verification, not merged — don't tell users this is solved yet. |
| Candidate-ranking framework (Sept §0, "candidates 1–5 from cheap to expensive") | the whole "find where the pipeline breaks" investigative approach | **Superseded, kept only as a methodology note.** None of candidates 1–5 (activation sequence, byte-layout misclassification, timestamp anchoring, warm-up XOR table, key-family fallback) predicted the real root cause (§1). Candidate 1 ("activation") was closest in spirit but the specific mechanism (missing `correctCode`) wasn't something static code reading could have found — it took a hardware A/B test and a decompile. Lesson for next time: when a protocol depends on a vendor cloud round-trip, "compare our decode against their decode" framings miss root causes that live entirely outside decoding. Candidate 5 (key-family selector unreliable on real sensors, confirmed independently via `ICanGlucoseDecode.swift`'s own real-device-driven fallback) remains a legitimate, separate, still-open finding — not explained away by `correctCode` and not acted on yet. |

---

## 5. Prioritized action list

**Updated 2026-10-22: #680 and #683 exist as drafts and change what "do this" means for several
items below — both need a hardware run before merge, neither has had one yet.**

1. **Hardware-run draft PR #683** (§1.8 + §1.9: ECB→CBC live decrypt, live-value semantics gate).
   Highest priority by impact: §1.9's classifier is the only mechanism in this whole document aimed
   at a plausible live, user-visible, wrong-but-in-range number *today*. Both commits are
   test-covered against captured bytes only — a real device run is what's missing, not more code.
2. **Hardware-run draft PR #680** (§1.5: `correctCode` write path). Second priority: unblocks F9
   properly (sensors JNG activates become fully functional, no vendor app ever needed) if the
   framing holds on real hardware. Track issue #679 for the still-unlanded server-fetch half.
3. **Merge PR #635.** §2.1's `0x2AAA` fix and the §4 F3/F4 `startCalib` fix are both written and
   tested; the only blockers are §1.6 (`planCalib` arity) and refreshing the stale PR body/review
   (§6) so they actually cover `d6fe2201d`, not just `d8d2d610d`.
4. **§1.4 — F6=0xFFFF diagnostic warning.** Low-risk, well-specified, no protocol change, no device
   test required, and not yet claimed by any draft PR as of 2026-10-22 — a good small first PR for
   anyone who wants one that isn't already spoken for.
5. **§2.2 — session-start writer byte-packing fix.** Independent of #3. Needs a real IANA/DST test
   zone, not `GMT±HH:mm` literals, to actually be covered by a test.
6. **§1.7 — history backfill policy.** No longer blocked on a device test (issue #676 already
   confirms the sensor serves old ranges) — this is now a deliberate trade-off decision (extra BLE
   time per connection vs. filling holes behind the live edge), not a prerequisite-test item.
7. **§1.6 — resolve the `planCalib` arity question** before anyone cites PR #635 as "fully
   verified." Either find the 01.18.00.19 bridge symbol or get the CE build's parameter semantics.
8. Before merging anything from `.worktrees/ican-anchor-trace` (`fix/ican-anchor-trace`): strip the
   temporary `ICanAnchorTrace.kt`/`Log.e` instrumentation, and rebase onto current `main` (its base
   `1e2a1f2b5` is now behind). Its remaining use is the residual zone edge case noted in §2.1, not
   the already-shipped device-zone fix.

Not on this list because there is nothing actionable yet: Candidate 5 (key-family fallback) from
the superseded Sept framework remains a real, independent, unaddressed finding
(`ICanHealthConstants.resolveBundledKeyFamily`, `ICanHealthConstants.kt:186` — on a misclassified
SN, JNG silently drops the packet instead of trying the other key family, unlike the independent
`ICanGlucoseDecode.swift` reference). Worth a small follow-up PR on its own; not urgent enough to
jump this queue.

---

## 6. Source map (so nobody has to re-derive this)

- **Primary external report** (correctCode mechanics, hardware A/B, CE-app unpacking tooling):
  [`docs/ican/ICAN-NOTES_2026-10-18-correctcode-investigation.md`](../../ican/ICAN-NOTES_2026-10-18-correctcode-investigation.md).
  Its own §10 is the same iPhone PacketLogger capture analyzed independently in §3 of this document
  and, in far more detail, in issue #676 below.
- **PR #635** (`t3/ican-history-fetch`, now at `d6fe2201d`, two commits: `d8d2d610d` `startCalib`
  fix + `d6fe2201d` `0x2AAA` fix). Open, not merged. §1.6 (`planCalib` arity) and the stale PR
  body/review (only covers the first commit — see §1.6) block treating it as fully verified and
  ready to merge as-is; the actual diff content looks sound.
- **PR #633** (merged): replays a 2026-10-08 trace to lock in the covered-edge anchor arithmetic —
  a different bug class (timestamp anchoring/skew-guard), not the `0x2AAA` zone issue in §2.1.
  Don't conflate the two "session timeline" stories.
- **PR #677** (`docs/ican-cgm-protocol`, open): publishes `docs/ican-cgm-protocol.md` — a scrubbed,
  shareable write-up of the GATT map, auth flow, history/RACP semantics, the 19-byte live packet
  layout, the provisioning gate, and the session clock, meant to replace ad hoc machine-local notes
  for whoever next touches the driver. Automated review (Greptile) flagged five documentation-
  accuracy issues in it worth knowing about if you read that doc instead of this one: the auth
  plaintext layout is missing two bytes, the "current" field is mislabeled as a glucose unit, the
  live-packet byte diagram has an extra byte, the activation command is attributed as `F5` when the
  driver actually sends `0x1A`, and — same issue as §1.6/§2.1 here — it currently overstates the
  `0x2AAA` fix as unconditionally shipped without noting what `parseSessionStartTime` actually does
  on `main` today (not yet merged). Not this document's PR to fix; flagged here so nobody trusts
  that doc's session-clock section over this one's §2.1 without checking which has been corrected.
- **Issue #676** (open): "iCan: follow-ups from the official iOS app's BLE traffic" — the GitHub-side
  tracking issue for §1.4, §1.7, §1.8, §1.9 above (follow-ups 1, 5, 2, 3 respectively in the issue's
  own numbering) plus a record of follow-up 4 (the `0x2AAA` fix, §2.1) as already implemented. Read
  this issue directly for the full per-opcode write tally and the exact CBC-decrypt byte evidence —
  §1.8/§1.9 here summarize it, not replace it. **Its "Please do not implement the correctCode write
  path" is superseded by issue #679 below** — read #679 before trusting #676's framing on that one
  point; everything else in #676 stands.
- **Issue #679** (open, 2026-10-19 → still active 2026-10-22): "iCan: provision a fresh sensor from
  our own app (no vendor app in the loop)" — §1.5's primary source. A fast-moving, multi-day,
  two-track investigation (write-framing reverse engineering + server-fetch reverse engineering)
  that has independently solved both halves as of the last read. Read it directly rather than
  trusting any snapshot, including this one — several of its own conclusions were withdrawn hours
  after being posted. Deliberately withholds credentials, tables, and raw captures; this document
  does the same.
- **PR #680** (draft, `wip(ican): write the correctCode table from our own activation
  (debug-gated)`): the write-path implementation tracked in §1.5. Off by default, 173 tests, no
  hardware run yet.
- **PR #683** (draft, `iCan: live notifications are CBC/PKCS7 + live-value semantics gate`): the
  implementation tracked in §1.8/§1.9. Two separable commits, 158 tests, no hardware run yet; its
  own first commit is flagged by its author as independently merge-worthy regardless of the second.
- **Branch `fix/ican-anchor-trace`**, worktree `.worktrees/ican-anchor-trace`: temporary trace
  instrumentation, now relevant to the residual zone edge case in §2.1 rather than to the (already
  shipped) device-zone fix itself. Strip before merge (§5 item 7).
- **This session's `.pklg` analysis** (§3): scripts were in the session scratchpad
  (`/private/tmp/claude-501/.../scratchpad/ican/parse_pklg*.py`), ephemeral — re-derive from the
  raw files in `~/Downloads/ican_1..4.pklg` if the exact per-packet transcript is needed again; the
  summary numbers in §3 are the durable record. Issue #676's own analysis of what is very likely the
  same capture is more thorough (per-handle opcode tallies, the CBC-decrypt proof) — treat issue
  #676 as the primary source where the two overlap, and §3 here as independent corroboration plus
  the disconnect-timing material issue #676 doesn't cover.
- `docs/architecture/session-handoff.md` (Room migration track — unrelated, but cross-references
  this work once, for the Conscrypt-provider-order issue that also affects the `icanhealth.*` unit
  tests): update its pointer to this file instead of the deleted Sept doc.
