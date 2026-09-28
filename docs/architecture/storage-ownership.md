# Storage ownership

Status: **first pass, against `main` at `05f9a9fb1`.** P4 ("one owner per kind of data") needs a
document to point at, and this is that document written down before anyone needs it to be true.
The ownership column is verified against today's code and every row cites where it was checked.
The other facets -- ordering, clock rollback, reconciliation, deletion -- are recorded where the
code states them plainly and marked **not established** where I did not verify them. Those gaps
are deliberate: an invented answer here would be worse than an open question, because Q3 and any
storage contract are supposed to be built on this file.

## What "owner" means

One authoritative writer per kind of data (P4). Everyone else reads, or reconciles. A second
writer is not a bug to be tolerated, it is a change that needs a line added to this file saying
why the single owner no longer suffices. "Reads" includes writing to its own cache; the test is
whether a reader can leave the datum in a state the owner would not have produced.

A variant that lacks a capability does not get to own a copy of the datum (P2, and the table in
`direction.md` §2: sensor state and secrets are "never exported, never mirrored to the watch").
This is the line the watch rows below sit on, but it is not a blanket "the watch never writes":
in direct-sensor mode the watch *is* the writer for the live reading, and for journal and
calibration it holds copies and issues commands. What the watch never becomes is the authority
on another device's state.

## Ownership

| Datum | Authoritative writer | On the watch |
| --- | --- | --- |
| Live reading (current value) | **not a single owner** -- see below: whichever device holds the sensor, decided at runtime | the watch is the writer in direct-sensor mode |
| History (readings over time) | `HistoryRepository` (Room, `src/mobile`) | **no `HistoryRepositoryAccess` registered** — reads only |
| Calibration (parameters, profile) | the phone, `CalibrationManager` (Room, `src/mobile`) | `SyncedWearCalibrationProvider` — holds a copy, commands over `/sync2/calcmd` |
| Journal (treatments, notes, entries) | the phone, via the journal accessors | holds a synced copy and issues commands over `/sync2/journal/cmd` |
| Sensor metadata + driver state + credentials | the sensor's own store: native mmap, and a prefs file per driver | never mirrored |
| Setting | the native settings block (`settings.hpp:315`, the bitfield struct) | its own local block; see the caveat |

Both `HistoryRepository` and `CalibrationManager` are in `src/mobile`, so the watch cannot reach
them even if it wanted to. `Specific.registerBridges()` makes that explicit: the phone registers
`MobileHistoryRepositoryBridge`, `MobileCalibrationProvider` and `MobileCalibrationProfileBridge`;
the watch registers only `SyncedWearCalibrationProvider`, and registers no history repository and
no calibration profile at all. That asymmetry is the enforcement for history: the watch registers no writer
at all, so there is nothing to drift. Calibration is weaker, because the watch
registers a synced provider -- the invariant there is not "the watch does not write" but "the
watch is never the authority", which is a rule rather than a compiler-enforced absence.

## The seven facets

### Identity

A datum is identified by (sensor, kind, time) for readings, and by (kind, primary key) for
journal and history rows. The plan calls for recording identity per datum; **the time component
of reading identity is not established** and is the first thing to pin down, because ordering and
clock rollback both depend on it. There is a standing example of the hazard: #424 exists because
a phone clock change could make a Chinese-protocol sensor look like it had restarted.

### Authoritative writer

Per the table above. Two rows carry more structure than the others:

- **Live reading has no single owner, and that is the important row.** Which device reads the
  sensor is decided at runtime: `WearSensorClaim` and `SensorOwnershipRuntime` own the claim, and
  the `/sensorhandoff` and `/sync2/own` messages move it between devices. In direct-sensor mode
  the **watch** writes the live reading and the native stream, and `WearSync2` carries those
  readings to the phone, which stores them in Room through `HistorySyncAccess.storeSensorHistoryBatchAsync`
  (`WearSync2.kt:513`). So ownership of this datum is per-device and conditional, and a
  document that names one writer for it would be wrong. The native mmap store is where the
  owning device keeps it, with `VirtualGlucoseSensorBridge` and `VirtualSensorNativeMirror`
  (both `src/main`) between it and the drivers; which of the three writes for a given driver is
  **not established**.

  One consequence worth watching: `storeSensorHistoryBatchAsync` is also called from a driver
  directly (`drivers/ottai/OttaiBleManager.kt:3445`), so on the phone there are two entry points
  into sensor history. Whether that is one writer reached two ways or a genuine second writer is
  **not established**, and it is the first thing to settle here -- the take-over case is exactly
  where a second writer would show up.
- **Journal.** The plan says "the journal accessors, one per kind". The registry carries several
  (`JournalAccess`, `JournalTreatmentUploadAccess`, `NightscoutTreatmentImportAccess`,
  `LibreviewJournalEntriesAccess`, `JournalSnapshotAccess`) and the phone registers each. Whether
  one of them is the writer or all of them are is **not established** -- `OutboundApiJournalSnapshot`
  was looked up in three places before Q1, so "one writer" is a claim to check rather than assume.

### What counts as a duplicate

Recorded, not guessed:

- A second **registration** of the same implementation in `registerBridges()`. The guards exist
  because `registerBridges()` runs twice per process (from `onCreate` and again from the
  variant's `start()` as a safety net), so a second `register` is a real event and not a
  theoretical one.
- A **Room write that bypasses the owner** -- an insert into the history or calibration tables
  from anything other than `HistoryRepository` / `CalibrationManager`.
- **Clone recovery is the known second writer**, and the plan calls it a fourth writer after the
  two Room owners and the native store. It is a whole subsystem in its own right: staging, an
  import ledger, a package IO layer, a wake path, and mobile-side coordinators and bridges. The
  ledger and staging classes are the design's answer to "how does a second writer stay
  reconcilable", and **how that reconciliation works is not established** -- it should be the
  next thing written here, because it is the only sanctioned second writer.
- A **native-side write** to a datum a Kotlin owner also writes. `settings.hpp:315` is the
  clearest case: `dontuseclose` is a bitfield in the persisted block, written by
  `javasettings.cpp:2272` and read straight off the struct at 2276 with no cache. That is why the
  `useclose` cache was removed in #477 -- the cache was a second writer with no owner.

### Ordering

**Not established, per datum.** The one thing visible from today's code is that freshness is
already computed in `src/main` by `DisplayDataState`, which takes
`maxOf(currentTimestampMillis, latestHistoryTimestampMillis)` and classifies the result against
`Notify.glucosetimeout`. So there is a live-vs-history reconciliation rule, and it is a *max*:
the newer of the two wins. That is a fact about the tie-break, not a proof of ordering.

### Clock rollback

**Not established, and with no concrete example yet.** One pressure is visible:

- `DisplayDataState` clamps `ageMillis` at 0 (`coerceAtLeast(0L)`), so a reading dated in the
  future is treated as brand new rather than rejected. Whether that is the intended reading of a
  forward clock jump is worth deciding explicitly, because "fresh" and "impossible" currently
  produce the same answer.

An earlier draft of this file claimed `dontuseclose` was a timestamp in the settings block. It is
not: `settings.hpp:315` declares it as a one-bit boolean, `bool dontuseclose : 1`. It appears in
this file only as an example of a cache being a second writer, under "what counts as a duplicate".

### Reconciliation

`DisplayDataState` is the only reconciliation step I could point at in shared code, and it is a
freshness classification, not a merge. For the watch, reconciliation is instead a *sync*: it
receives data and has no writer (see the table). For clone recovery, reconciliation is the
`CloneRecoveryImportLedger`, whose semantics are **not established**.

### Deletion

Traced per driver, and it is **not uniform**. Two different lifetimes for two kinds of
credential, which is the finding here.

**iCan ties its secret to the sensor.** `ICanHealthRegistry.removeSensor` drops the record set
and then removes, in one commit, `PREF_AES_KEY_PREFIX`, `PREF_DEVICE_SN_PREFIX`,
`PREF_AUTH_USER_ID_PREFIX`, `PREF_RECOVERED_USER_ID_PREFIX`, `PREF_AUTH_BYPASS_UNTIL_PREFIX` and
the two history-edge keys. The AES keys also have hardcoded defaults in `ICanHealthConstants`
(`DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII` and friends), which are source constants and no deletion
touches them -- so deleting a sensor removes that sensor's stored key, not the vendor default
that ships in the binary.

**AiDex does not tie its secret to the sensor.** `AiDexNativeSensorManager.removeSensor` clears
the main sensor, destroys the BLE manager, drops the entry and persists the shortened list; it
does not touch `AiDexPairingMaterialFile`, and that class has no delete or remove method at all
-- only `normalizeSerial`, `encode` and `decode`. The pairing material *is* removable, but by a
separate manual action: `AiDexKeyManagementScreen`, reached from `SensorCard` with
`aidex_pairing_key_delete` / `_delete_confirm` / `_deleted`. So a user who removes a sensor and
never visits that screen keeps that sensor's pairing material on disk.

**History is deletable, and it is reachable.** `HistoryDao.deleteForSensor` deletes
`history_readings` by serial, called from `HistoryRepository.deleteForSensor` and, from
`HistorySync.kt:475`, for the serial and for each of its legacy aliases. A second path is the
fuller one: it deletes the same rows and additionally clears `reading_uncertainty` and the display
table, and its own comment notes that unlike `deleteForSensor` nothing re-syncs afterwards. The
`deleteReadingsForSensorAfter` calls in `VirtualGlucoseSensorBridge.pruneFutureHistory` and
`ICanHealthBleManager` are **not** deletion -- they trim rows past a cutoff (future-dated rows, a
history boundary), and reading them as deletion would overstate what the code does.

What is still open, and is the reason this section is not finished: no path found removes the
native mmap bytes for a removed sensor, and no path found coordinates the driver-level removal
with the Room purge or the watch's copy. The six `removeSensor` implementations (AiDex, Anytime,
ICan, MQ, Ottai, Sibionics) are per driver, with no shared contract, so "what removal guarantees"
currently has no single answer -- and the credentials row above shows the two drivers that manage
a secret already answer it differently.

## Credentials and per-driver state, deliberately outside the settings registry

The plan is explicit that the settings registry **excludes** per-sensor driver state and
credentials, and today's code agrees: these live in their own files next to the drivers rather
than in the settings block.

- **AiDex pairing keys** — `drivers/aidex/AiDexPairingMaterialFile.kt` is a dedicated file, not a
  settings key. `AiDexDriver` and `AiDexNativeFactory` reach it; `ManagedSensorHandoff` in
  `src/main` is the shared-code entry point.
- **iCan AES keys** — under `drivers/icanhealth/` (`ICanHealthConstants`, `ICanHealthParser`,
  `ICanHealthBleManager`).
- **MQ** state lives in `MQRegistry` in `tk.glucodata_preferences`. Out of scope for this
  document and not covered here.

The reason for the exclusion is the one worth keeping: these are per-sensor secrets with a
lifetime tied to a pairing, not user preferences. Mirroring one to the watch would mean a second
copy of a secret on a second device, which is the `secret: true` case Q3 has to ask about anyway.

## Consequence for the other queue items

- **Q2** (typed phone↔watch protocol) does not collide with anything here, because the watch is
  never the authority on any row -- but it is not simply a reader either. In direct-sensor mode it
  writes the live reading natively and `WearSync2` ships it in; for journal and calibration it
  holds copies and sends commands. A codec has to carry all three shapes, and the version field
  has to distinguish "I am reporting my own reading" from "I am applying your decision".
- **Q3** (D1 watch features: IOB/COB, standalone Nightscout, journal and meal entry) is the item
  that **needs this file**. "Journal/meal entry on the watch" and "standalone Nightscout" are both
  second-writer questions, and the secrets question is the one to settle first: if the watch holds
  a Nightscout API secret, that secret has an owner and a deletion rule, and neither exists yet.
- **Q5 is this file.** The remaining gaps above are the work, and they should be closed before
  Q3 starts rather than during it.

## Validation

Everything in the ownership and credentials tables was read out of the tree at `05f9a9fb1`, not
recalled:

- `HistoryRepository` and `CalibrationManager` are in `src/mobile`; `VirtualGlucoseSensorBridge`
  and `VirtualSensorNativeMirror` are in `src/main`.
- The phone's `registerBridges()` registers `MobileHistoryRepositoryBridge`,
  `MobileCalibrationProvider`, `MobileCalibrationProfileBridge`; the watch's registers
  `SyncedWearCalibrationProvider` and no history repository.
- `dontuseclose` is `settings.hpp:315`; `javasettings.cpp:2272` writes it and 2276 reads it.
- `DisplayDataState` takes `maxOf(current, latestHistory)` and clamps `ageMillis` at 0.
- Clone recovery's file list is the `CloneRecovery*` / `CloneOutgoingRecovery*` set in `src/main`
  plus the mobile coordinators and bridges.
- AiDex pairing material has its own file, with no delete method; iCan AES keys are per-sensor
  prefs keys that `ICanHealthRegistry.removeSensor` does remove, plus hardcoded vendor defaults
  in `ICanHealthConstants` that no deletion reaches.
- `HistoryDao.deleteForSensor` is reachable from `HistorySync.kt:475`; the
  `deleteReadingsForSensorAfter` calls in `VirtualGlucoseSensorBridge` and `ICanHealthBleManager`
  are cutoff trims, not deletion.

Facets marked **not established** were not verified and are not guesses -- they are the open
questions above. Documentation only: no app code was changed, and no build or device run was
performed for this file.
