# iCan trace deep-dive — plan

Trace: `~/Downloads/juggluco-trace-20261008-175444.log` (device SM-S938B, 17:40–17:54 +03).
PR in flight: ctqvva/JugglucoNG#600 (`fix/ican-live-timestamp-and-ghost-main`).

## What PR #600 already covers (symptoms, not root causes)
1. Ghost Libre2 `8EB506B` held the main-sensor role, so iCan notifications were skipped.
2. Live readings were stored ~1h in the past because the persisted covered edge was skewed.

PR #600 papers over both at the point of use. Two root causes are still unknown:
**who skewed the edge**, and **who deleted the Libre2 directory while its slot stayed mapped**.

## Questions for the deep trace (priority order)

### Q1. Where does the 1h skew in the covered edge come from?
- Evidence: seq 17070 ↔ 16:38:01, true time 17:40:32; stored stamps end in `:01`
  (history-record-like, not live receipt time). Session-start drift is 178–180 min (UTC+3).
- Read: `rememberCoveredEdge` call sites (history path ~L3009), `resolveHistoryTimestampMs`,
  `HISTORY_TIMEZONE_REPAIR_GENERATION` repair flow, `canUseHistoryPastEndedStatusCap`.
- Hypothesis A: the TZ repair re-pull stored records with an offset that is off by exactly
  one hour (60 min = 180 − 120?) and wrote the edge from it.
- Hypothesis B: the sensor's seq counter pauses while disconnected, so edge + Δseq drifts.
  Test: do seq deltas between notifications (3 per 180 s) ever disagree with wall clock?
- Needs: prefs `icanhealth_history_edge_*` for `83D0054400070000` (adb `run-as`), and any
  earlier trace covering the last history download (before 16:23).

### Q2. Why did the Libre2 directory vanish while the slot stayed live?
- Evidence: `getSensorData(0) D4FE28EB506B ... No such file`, self-heal only at process start.
- Read: `terminateManagedSensor`, MQ/onboarding removal paths, `usedsensorssnapshot`,
  `Natives.activeSensors`, how `hist[0]` stays valid after the directory is deleted (mmap).
- Confirm the hypothesis that `activeSensors` kept listing it; PR #600 assumes this.

### Q3. `FindClass(tk/glucodata/SensorIdentity) failed` (native, every resume)
- Possibly a native-thread classloader problem that makes native-side main resolution
  silently fail. Check whether any native path depends on it and whether it relates to Q2.

### Q4. Parsed 1.7 mmol/L vs notified 3.6 mmol/L
- Trace: `glucose field ... -> 1,7 mmol/L (30,3 mg/dL)` then `SuperGattCallback ... glucose=3.5777762`.
- Read: `handleGlucoseResult`, calibration (`ICanHealthCeCalibrationTests`), raw lane.
- Decide: intended calibration or a bug.

### Q5. Count/id gap
- `savepollallIDsonly count=17004<id=17006` at the first reading: 2 ids skipped. Check
  whether this is a history hole created by the 1h-shifted timeline.

## Method
1. Agent A (trace analyst): timeline of the log, per-reading table
   (receipt time, seq, stored time, drift), answers Q1/Q4/Q5 from evidence only.
2. Agent B (code tracer): follows Q1 and Q2 through the code, lists every writer of the
   covered edge and every remover of a sensor directory, with file:line.
3. Merge findings, then decide what extends PR #600.

## Deliverables for the PR
- Root-cause fix for Q1 (stop writing a skewed edge), keeping the PR #600 guard as defence.
- Fix or guard for Q2 at the removal site, not only at the listing site.
- Replay tests built from trace values (seq 17070–17085, start=1790456925000).
- Do not touch CT3/CT2.5/CT4/CT5 (project rule).

## Out of scope
- Rewriting readings already stored with the wrong time.
- Warm-up ramp or any algorithm behaviour.
