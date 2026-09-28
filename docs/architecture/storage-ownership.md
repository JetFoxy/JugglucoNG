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

A history row is identified by **(`sensorSerial`, `timestamp`)**, enforced as a unique index on
`history_readings` (`HistoryReading.kt:19`); the `@PrimaryKey` is an auto-generated `id`, so the
identity is the pair, not the row. `HistoryReading` also carries `value`, `rawValue`, nullable
`rate`, `source` and `firstStoredAt` -- the last being insertion time, which records when the app
learned of a reading, not when the sensor saw it.

**The resolution of `timestamp` is not uniform across write paths, and this is the part worth
knowing.** All seven sensor producers arrive through `storeSensorHistoryBatch*`, which ends at
`storeReadingsReplacingSensorBuckets`, and that path calls
`HistoryBucketReplacement.collapseReadings(..., SENSOR_MINUTE_BUCKET_MS = 60_000)` before writing.
It keys on `timestamp / bucketDurationMs` and keeps **one row per 60-second bucket**. The
single-reading path (`storeReading`) does not collapse, so it can write a sub-minute timestamp.

So a reading's identity is exact to the millisecond on one path and quantised to the minute on the
other, and the unique index only ever collides on exact equality. A sub-minute row from the single
path therefore coexists with a bucket row for the same minute instead of replacing it. Which of the
two happens is decided by the entry point a producer used, not by anything the datum itself asserts
-- worth settling, because it is the same shape of problem as the take-over case below.

Readings with `timestamp <= 0` are dropped by the collapse rather than stored.

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

  History has **one writer with many producers**, and that is worth stating precisely because I
  first got it wrong in the other direction. `HistorySyncAccess.store*` is called from
  `SuperGattCallback` (native-backed sensors), from every managed driver -- `OttaiBleManager`,
  `SibionicsBleManager`, `AiDexBleManager`, `ICanHealthBleManager` -- from
  `VirtualGlucoseSensorBridge`, and from `WearSync2` for readings the watch took over. All of
  them land in `HistoryRepository`, so the datum has an owner and the producers are not writers.

  The open question here is not "how many producers" but **which device's producer is
  live for a given sensor at a given moment**, which is what `SensorOwnershipRuntime` decides. A
  take-over is the case worth naming, because two producers can briefly overlap, and what a double
  write does is settled by the schema rather than by policy: `history_readings` carries a unique
  index on `(timestamp, sensorSerial)` (`HistoryReading.kt:19`), and `HistoryDao` offers both
  `OnConflictStrategy.REPLACE` (`insert`, `insertAll`) and `OnConflictStrategy.IGNORE`
  (`insertAllIgnoring`, `insertDeletedReadings`).

  **Traced, so the take-over case does not have to be guessed at.** Every one of the seven sensor
  producers lands on `REPLACE`: they all enter through `HistorySyncAccess.store*`, which delegates
  to `HistoryRepository.storeReading` / `storeReadings` / `storeReadingsReplacingSensorBuckets`,
  and those call `dao.insert` / `dao.insertAll`. `IGNORE` is used in exactly one place,
  `CloneGlucoseRecoveryStore` (`historyDao.insertAllIgnoring` and `uncertaintyDao.insertAllIgnoring`,
  both guarded by a non-empty check), so clone recovery is deliberately first-write-wins while the
  sensor path is last-write-wins.

  The "last write wins" is not quite the whole story, and the part that matters was already
  handled: before the batch insert, the code reads the existing row by `(sensorSerial, timestamp)`
  and folds it in, via `HistorySourceProvenance.stableSource` and `stableFirstStoredAt`. So
  `source` and `firstStoredAt` survive a take-over. The measurement columns do not: on one sensor
  and one minute the later producer's `valueMgdl`, `rawValueMgdl` and `rate` replace the earlier
  row's outright. So a take-over can change what a reading says, while its provenance stays
  honest -- which is the correct split, and it is a property of the code rather than of a rule.

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

Rows are ordered by `timestamp`, and the secondary index on `(sensorSerial, timestamp)` exists to
serve that. That much is settled.

**Within a minute there is no order on the batch path**, because `collapseReadings` discards
sub-minute positions: a bucket yields exactly one row. The choice of which reading represents a
bucket is a real policy, not an accident -- `choosePreferred` scores each candidate, +10 for a
finite positive `value` and +5 for a finite positive `rawValue`, and only when the scores tie does
it fall back to the **later** timestamp. So quality beats recency, and recency is only the
tiebreak. A minute whose two readings both look valid is represented by the later one; a minute
where only the raw value is usable keeps the reading that at least has a value.

`firstStoredAt` does not help here: it is when the app stored the row, so on a re-sync it moves for
a reading whose sensor time did not.

Note that the display layer merges on the same 60-second granularity
(`HistoryDisplayMerge`, with its own `SENSOR_MINUTE_BUCKET_MS`), which is consistent with the write
path but for a different reason: display grouping, not storage identity.

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
- `history_readings` has a unique index on `(timestamp, sensorSerial)`, and `HistoryDao` mixes
  `REPLACE` and `IGNORE` inserts, so a take-over's outcome depends on the insert chosen -- see the
  live-reading row.
- Clone recovery's file list is the `CloneRecovery*` / `CloneOutgoingRecovery*` set in `src/main`
  plus the mobile coordinators and bridges.
- AiDex pairing material has its own file, with no delete method; iCan AES keys are per-sensor
  prefs keys that `ICanHealthRegistry.removeSensor` does remove, plus hardcoded vendor defaults
  in `ICanHealthConstants` that no deletion reaches.
- `HistoryDao.deleteForSensor` is reachable from `HistorySync.kt:475`; the
  `deleteReadingsForSensorAfter` calls in `VirtualGlucoseSensorBridge` and `ICanHealthBleManager`
  are cutoff trims, not deletion.

Facets marked **not established** were not verified and are not guesses -- they are the open
questions above. The identity, ordering, live-reading, duplicates and deletion facets are now
traced; clock rollback and reconciliation are the two that remain open.

Additional verification for this pass:

- `HistoryReading` declares `@PrimaryKey(autoGenerate = true) id` plus the unique index on
  `(timestamp, sensorSerial)`, and its own comment gives the reason: the same timestamp from
  different sensors must coexist.
- `HistoryBucketReplacement.collapseReadings` keys on `timestamp / bucketDurationMs`, keeps one row
  per bucket, drops `timestamp <= 0`, and returns them sorted by timestamp.
- `choosePreferred` scores +10 for a finite positive `value`, +5 for a finite positive `rawValue`,
  and breaks a tie on the later timestamp.
- `SENSOR_MINUTE_BUCKET_MS = 60_000` is declared in `HistoryDisplayMerge.kt`, i.e. the display
  layer's own copy of the same granularity. Documentation only: no app code was changed, and no build or device run was
performed for this file.
