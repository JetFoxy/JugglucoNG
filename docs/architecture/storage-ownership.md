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
This is the line the watch rows below sit on, and it is why so many watch rows read "not
registered" rather than naming a writer.

## Ownership

| Datum | Authoritative writer | On the watch |
| --- | --- | --- |
| Live reading (current value) | the native mmap store, via the driver that produced it | reads the phone's, never owns |
| History (readings over time) | `HistoryRepository` (Room, `src/mobile`) | **no `HistoryRepositoryAccess` registered** — reads only |
| Calibration (parameters, profile) | `CalibrationManager` (Room, `src/mobile`) | `SyncedWearCalibrationProvider` — a reader, not an owner |
| Journal (treatments, notes, entries) | the journal accessors, one per kind, Room | not registered; phone-only by design (P3) |
| Sensor metadata + driver state + credentials | the sensor's own store: native mmap, and a prefs file per driver | never mirrored |
| Setting | the native settings block (`settings.hpp:315`, the bitfield struct) | its own local block; see the caveat |

Both `HistoryRepository` and `CalibrationManager` are in `src/mobile`, so the watch cannot reach
them even if it wanted to. `Specific.registerBridges()` makes that explicit: the phone registers
`MobileHistoryRepositoryBridge`, `MobileCalibrationProvider` and `MobileCalibrationProfileBridge`;
the watch registers only `SyncedWearCalibrationProvider`, and registers no history repository and
no calibration profile at all. That asymmetry is the enforcement, and it is why those two entries
cannot drift: the watch has no writer to drift with.

## The seven facets

### Identity

A datum is identified by (sensor, kind, time) for readings, and by (kind, primary key) for
journal and history rows. The plan calls for recording identity per datum; **the time component
of reading identity is not established** and is the first thing to pin down, because ordering and
clock rollback both depend on it. There is a standing example of the hazard: #424 exists because
a phone clock change could make a Chinese-protocol sensor look like it had restarted.

### Authoritative writer

Per the table above. Two rows carry more structure than the others:

- **Live reading.** The plan describes the native mmap store as the owner, with
  `VirtualGlucoseSensorBridge` and `VirtualSensorNativeMirror` (both `src/main`) sitting between
  it and the drivers. Which of the three is the writer for a given driver, and what the mirror
  does on a phone-only sensor, is **not established** -- it is the ownership question this file
  most needs answered, and the answer may be per-driver rather than global.
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

**Not established.** Two known pressures, neither of which this file answers:

- `dontuseclose` and its neighbours are timestamps inside a persisted block, so a phone clock
  change moves them; #424 is the tracked instance of the general problem.
- `DisplayDataState` clamps `ageMillis` at 0 (`coerceAtLeast(0L)`), so a reading dated in the
  future is treated as brand new rather than rejected. Whether that is the intended reading of a
  forward clock jump is worth deciding explicitly, because "fresh" and "impossible" currently
  produce the same answer.

### Reconciliation

`DisplayDataState` is the only reconciliation step I could point at in shared code, and it is a
freshness classification, not a merge. For the watch, reconciliation is instead a *sync*: it
receives data and has no writer (see the table). For clone recovery, reconciliation is the
`CloneRecoveryImportLedger`, whose semantics are **not established**.

### Deletion

**Not established for every datum.** What deleting a sensor, a treatment, or a history window
actually removes -- Room rows, native mmap bytes, prefs files, and mirrored watch state -- is
unverified. This matters more than it looks: the plan's own table says credentials are "never
exported, never mirrored", so a delete that only clears the phone's rows would leave a secret in
a prefs file. That needs to be answered before any storage contract exists.

## Credentials and per-driver state, deliberately outside the settings registry

The plan is explicit that the settings registry **excludes** per-sensor driver state and
credentials, and today's code agrees: these live in their own files next to the drivers rather
than in the settings block.

- **AiDex pairing keys** — `drivers/aidex/AiDexPairingMaterialFile.kt` is a dedicated file, not a
  settings key. `AiDexDriver` and `AiDexNativeFactory` reach it; `ManagedSensorHandoff` in
  `src/main` is the shared-code entry point.
- **iCan AES keys** — under `drivers/icanhealth/` (`ICanHealthConstants`, `ICanHealthParser`,
  `ICanHealthBleManager`).
- **MQ per-sensor calibration parameters** — the plan names these; my search pattern did not find
  them under that name, so **their location is unverified.** Recorded as an open item rather than
  guessed, because "the settings registry excludes them" is a claim about a file I did not read.

The reason for the exclusion is the one worth keeping: these are per-sensor secrets with a
lifetime tied to a pairing, not user preferences. Mirroring one to the watch would mean a second
copy of a secret on a second device, which is the `secret: true` case Q3 has to ask about anyway.

## Consequence for the other queue items

- **Q2** (typed phone↔watch protocol) is a *reader* concern, so it does not collide with anything
  here. The watch is a reader for every row in the table.
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
- AiDex pairing material has its own file; iCan AES constants live under `drivers/icanhealth/`.

Facets marked **not established** were not verified and are not guesses -- they are the open
questions above. Documentation only: no app code was changed, and no build or device run was
performed for this file.
