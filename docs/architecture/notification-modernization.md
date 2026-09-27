# Notification modernization proposal

Status: proposed feature plan for a documentation-only PR; it does not change
the structural track order in [direction.md](direction.md). Reviewed 2026-09-27
against main at 7b12a40d4. This is source and issue review only: no device
reproduction or timing measurement has been completed.

## Outcome and scope

Make the ongoing phone notification a timely, accessible view of the same
resolved glucose state as the dashboard, with explicit freshness and useful
compact and expanded layouts. It must work while the activity is closed and
after process restart. Preserve ingestion, storage, calibration, units,
sensor policy and independent glucose-alarm delivery.

N0 and a trimmed N1 are the immediate reliability work. N2 through N5 remain
deferred under D4. The primary surfaces are the dashboard adapter and ongoing
phone notification. WidgetDisplaySource/ExpressiveAppWidget, Floating,
GlucoseUpdateBroadcaster and other notification consumers remain compatibility
surfaces; they are not implicitly migrated by N1.

This plan does not add a journal action. Journal interaction needs its own
serious design review. It does not add a lock-screen privacy preference.

## Evidence in current source

Paths below are relative to Common/src/.

| Finding | Evidence | Planning consequence |
| --- | --- | --- |
| Freshness is 330 seconds | Notify.glucosetimeout is 30 * 11 seconds; CurrentDisplaySource.resolveCurrent uses it as max age. | N1 schedules an explicit stale transition at this boundary; freshness is not notification lifetime. |
| Startup has a longer history fallback | getforgroundnotification resolves current, then calls NotificationHistorySource.getDisplayHistory. On phone, HistoryRepository.getHistoryForNotificationForSensor reaches Room through runBlocking before foregroundno calls startForeground. A latest history point may be used when it is within 15 minutes. | Show a short FGS “Restoring readings” placeholder first and restore asynchronously. Do not confuse a stale history fallback with a fresh reading. |
| Restore failure and no current are collapsed at one boundary | resolveNotificationCurrentSnapshot catches any Throwable, logs and returns null. showoldglucose, glucoseRefreshRunnable and dataChangedGlucoseRefreshRunnable return when the snapshot is null or below the valid-value guard. | N1 must distinguish restore/query failure, no reading and stale reading, and publish a truthful state without inventing a value. |
| Ordinary builder lifetime and timestamp are different from alarm behavior | makearrownotification calls setTimeoutAfter(glucosetimeout), then stamps ordinary notif.when with System.currentTimeMillis(). The alarm builder stamps notif.when with glucose.time and posts glucosealarmid. | Record the lifetime change as a recommendation pending device evidence; do not change alarm timeout or Wear auto-cancel as a side effect. |
| Publish target depends on service and variant | fornotify sends glucosealarmid on Wear; on phone it calls startForeground(glucosenotificationid, notif) when keeprunning.theservice exists and otherwise calls notificationManager.notify(glucosenotificationid, notif). foregroundno builds history-backed content before startForeground. | Preserve service readiness, ordinary notify and Wear paths as separate acceptance cases. Never stop a required service implicitly. |
| Status-only refresh does not schedule notification work | UiRefreshBus.requestDataRefresh schedules the bounded refresh; requestStatusRefresh emits StatusOnly and invalidates Floating only. | N1 may add a contained status-to-notification invalidation and tests; do not invent a new state architecture. |
| Data refresh is a resettable trailing debounce | scheduleDataChangedNotificationRefresh removes and reposts a 1,000 ms callback. isSameForegroundGlucose compares only time, primary value and rate. | Bound the refresh policy and expand the render/update reason only when N0 reproduces the gap. |
| Text and charts have separate accessibility constraints | makearrownotification uses RemoteViews text for the current path, while startup and chart/arrow paths still rasterize selected content. | Native TextView replacement is acceptable where it materially improves accessibility, font scaling or allocation cost. M3 does not require abandoning IBM Plex; retain it where it works and measure the tradeoff. |

The seven synchronous showoldglucose callers are:

1. [DashboardViewModel.kt:1719](../../Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt#L1719),
   notification-chart setting path.
2. [DashboardViewModel.kt:2094](../../Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt#L2094),
   notification-prediction refresh path.
3. [GlucosePaletteState.kt:92](../../Common/src/mobile/java/tk/glucodata/ui/GlucosePaletteState.kt#L92),
   refreshNotification.
4. [ICanHealthBleManager.kt:282](../../Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt#L282),
   foreground refresh runnable.
5. [OutboundApiJournalSnapshot.kt:102](../../Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt#L102),
   journal-change worker.
6. [OutboundApiJournalSnapshot.kt:252](../../Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt#L252),
   journal refresh path.
7. [Applic.java:1373](../../Common/src/main/java/tk/glucodata/Applic.java#L1373),
   sensor/settings resume path.

The two Notify-internal calls are separate from those seven. Moving all of
these calls off the main thread is an N3 extraction/behavior boundary; N1 does
not edit drivers or perform a broad caller migration.

Relevant source links: [Notify.java](../../Common/src/main/java/tk/glucodata/Notify.java),
[UiRefreshBus.kt](../../Common/src/main/java/tk/glucodata/UiRefreshBus.kt),
[CurrentDisplaySource.kt](../../Common/src/main/java/tk/glucodata/CurrentDisplaySource.kt),
[DisplayDataState.kt](../../Common/src/main/java/tk/glucodata/DisplayDataState.kt),
[NotificationHistorySource.kt](../../Common/src/main/java/tk/glucodata/NotificationHistorySource.kt),
[HistoryRepository.kt](../../Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt),
[GlucoseUpdateBroadcaster.kt](../../Common/src/main/java/tk/glucodata/GlucoseUpdateBroadcaster.kt),
[WidgetDisplaySource.kt](../../Common/src/main/java/tk/glucodata/WidgetDisplaySource.kt).

## Product and lifetime policy

Reading freshness, transport status, service readiness and history-loading
status are independent. A reconnecting sensor can still have a fresh reading;
a Room exception is not an empty history result.

N1 recommendation, pending maintainer confirmation and device/OEM evidence:
the ordinary phone notification should publish a stale state at the 330-second
freshness deadline, retaining the last reading with an explicit age/timestamp
and no current arrow or prediction. Its lifetime should be governed by the
existing display/service modes, rather than allowing setTimeoutAfter(glucosetimeout)
to cancel it while a required service or display mode still requires it. Isolate
that builder-lifetime change from the stale-state patch. If the product keeps a
builder timeout during the experiment, it must be tested as a separate,
observable failure mode. Alarm notification lifetime and Wear auto-cancel remain
unchanged.

The current mode gates are evidence, not a guessed truth table:

| Mode | Current source behavior to preserve during N1 |
| --- | --- |
| Phone showalways=true | normal readings use the ordinary glucose notification; keep it while existing service/display rules require it. |
| Phone showalways=false | Notify.glucosestatus(false) calls novalue, replacing/canceling the ordinary display; a normal reading path cancels the ordinary ID when no service is running, while a running service receives its service notification. N1 must preserve this choice. |
| Phone alertwatch=true | a genuine per-reading delivery uses once=false, high priority and alarm category through arrowglucosenotification; preserve its mirroring intent. An age/settings/chart redraw is visual-only and must not create a duplicate alarm-style delivery. |
| No sensor | A service-only ongoing placeholder is tentatively supported when the service is actually required. No notification change may stop that service implicitly. |
| Wear | fornotify uses glucosealarmid, OngoingNotificationAccess and Wear's existing auto-cancel behavior. Do not reuse phone lifetime assumptions. |

The coordinator must carry an explicit update reason/mode: genuine reading
delivery, status, age/stale transition, settings/theme, history/chart or
startup restore. Only the first may preserve alertwatch's per-reading
mirroring intent. Rendering must not store, invent or rebroadcast a glucose
reading. GlucoseUpdateBroadcaster remains a compatibility consumer until a
separate migration proves otherwise.

Android 16 AOSP currently excludes FLAG_FOREGROUND_SERVICE from the timeout
cancellation mask in NotificationManagerService
[source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#2158).
That is useful evidence for the FGS path, not a blanket Android/OEM product
guarantee; device testing is still required.

## Immediate work: N0 and trimmed N1

### N0: evidence and characterization

Use the following local report keys for the user's 2026-09-27 thread reports;
they are not invented GitHub issue numbers:

- thread-2026-09-27-startup: startup content can be misleading or delayed.
- thread-2026-09-27-lag: ongoing notification may update only after opening the app.
- thread-2026-09-27-stopped-feed: stopped input may leave a fresh-looking value.

GitHub issue [#58](https://github.com/ctqvva/JugglucoNG/issues/58) is an open
Pixel 8/GrapheneOS lag regression target. It does not establish the cause of
the thread reports. Closed issue
[#14](https://github.com/ctqvva/JugglucoNG/issues/14) is a compatibility
reference for disabled glucoseNotification breaking glucoseAlarm/band mirroring;
it is not evidence to change alert behavior.

Characterize cold start, process recreation, no sensor, stopped feed, status-only
changes, history catch-up, screen off/Doze, resume and recovery with a fake
clock and recording publisher. Capture resolver outcome, source timestamp,
notification ID, post/startForeground path, visible-shade latency and
duplicate/broadcast side effects. Reproduce each report or label it
unobserved. Pin current behavior before moving code.

### N1: contained reliability fix

N1 may include only these behavior fixes and their focused tests:

1. Post a minimal FGS startup header (“Restoring readings”) early, then resolve
   Room/history asynchronously. Replace it with current, stale, waiting,
   no-sensor or temporary-unavailable text after restore. The header must not
   claim a sensor connection or display an empty grid as data.
2. Add an explicit reading timestamp/age to the high-value header early in the
   update. At 330 seconds, schedule/reconcile a stale state even without a new
   reading. Retain stale content only while the existing showalways,
   alertwatch, hasvalue or required-service mode says the notification belongs;
   cancellation remains an explicit mode/service result.
3. Reconcile on service restart, screen-on/resume and available time-change
   events. Do not promise exact Doze timing. A bounded eventual update is the
   acceptance target.
4. Make status-only refresh schedule the same bounded notification invalidation
   when the existing mode requires it. Keep the current 1-second trailing rate
   limit, but prevent an unbounded reset loop when N0 demonstrates starvation.
5. Deduplicate by a complete visible/update identity, including sensor/source,
   reading timestamp/value/rate, freshness/status and relevant display revision.
   Do not let age, settings or chart refresh invoke alertwatch delivery.
6. Keep normal phone notify versus startForeground, Wear glucosealarmid,
   alert timeout and Wear auto-cancel as explicit test cases. Do not touch
   glucose drivers or perform the seven-caller off-main extraction here.

N1 acceptance includes fresh-to-stale with no new input, stale-to-fresh
recovery, a service-only no-sensor case, showalways=false, alertwatch genuine
new-reading versus visual-only refresh, ordinary notify versus startForeground,
Wear timeout/auto-cancel, Doze timestamp handling and resume reconciliation.
OS and OEM timing claims require device evidence.

## Deferred work under D4 and P5

Structural and behavior changes must not share a PR. N2 through N5 wait for
D4's one structural track or an explicit amendment:

| Step | Deliverable | Acceptance |
| --- | --- | --- |
| N2 structural pilot | Extract ongoing presentation/coordinator/renderer/publisher seams as delegating adapters, with no behavior change. | Characterization parity, startup registration, no new storage owner, phone and Wear checks. |
| N3 shared-state adoption | Dashboard and notification consume the shared resolved state; move the seven showoldglucose callers behind an off-main contract; retain widget, Floating and broadcaster compatibility. | Matching values, units, trend and freshness; delayed history cannot overwrite live state; no extra store/rebroadcast. |
| N4 visual replacement | Improve compact/expanded hierarchy, native text/icons and chart accessibility after N1 is stable. Choose bitmap versus TextView per measured accessibility/performance and IBM Plex compatibility evidence; keep a bounded RemoteViews chart and useful standard notification text. | Units/locales, large fonts, TalkBack, light/dark, compact/expanded and multiple sensors. No journal action, privacy preference or frame-by-frame animation in this step. |
| N5 alert presentation | Apply shared visual vocabulary to alarm cards and actions without changing alert lifetime, sound, DND, retries, alertwatch or Wear behavior. | Cold-start actions and alarm regressions pass; delivery remains independent. |

Optional MetricStyle work is a later experiment, not an N1 dependency. The
installed API 37 SDK contains Notification.MetricStyle (javap inspection of
$ANDROID_HOME/platforms/android-37.0/android.jar on 2026-09-27 showed
addMetric, setCriticalMetric and setMetrics). Runtime availability, layout,
locale/decimal behavior and supported-device behavior remain untested.

## Validation and evidence gaps

For implementation PRs, add deterministic tests for no sensor, first reading,
restore unavailable/error, stale deadline without events, reconnect, history-only
startup, delayed Room write, backfill, equal-value source handover, peer/status/
unit/mode changes, out-of-order chart work, failed publication and clock
changes. Preserve mg/dL and mmol/L, provenance, gaps, smoothing and calibration;
a display refresh never writes a new reading.

N1 focused tests must cover ordinary notify versus foreground service and Wear
timeout/auto-cancel, alertwatch new-reading versus visual-only refresh,
showalways=false, stale/resume reconciliation and Doze timestamp behavior.
Keep NotificationScreenOnTests, NotificationChartSmoothingTests,
NotificationChartGapTests, NotificationPredictionBatchTests,
AlertNotificationLifetimeTests, AlertNotificationValueTests and
SilentNotificationAlertTests. Device evidence must include a current Pixel and another OEM, Android
12+, screen off, lock screen, Doze, process restart/reboot and denied
notification permission.

Proposed awake targets are hypotheses, not guarantees: p95 essential text within
500 ms of a resolved state and final chart within 1 s on the agreed reference
device. Notification rate limits and a bounded eventual latest update are part
of the contract. Measure app-state-to-notify/startForeground separately from
visible-shade latency, along with chart allocations and wakeups; age-only updates
must not redraw a chart.

Implementation checks follow project CI: full mobile and Wear JVM suites, both
debug variants, and release builds when notification/resources/bridges change.
This documentation-only revision ran no Gradle or device checks.

The modernization is complete when the notification follows the resolved state
with the activity closed, ages honestly when input stops, restores without
misleading startup content, remains readable and accessible, and retains
independent alarm behavior with measured update and allocation evidence.

[custom layouts](https://developer.android.com/develop/ui/views/notifications/custom-notification) |
[RemoteViews](https://developer.android.com/reference/android/widget/RemoteViews) |
[notification updates](https://developer.android.com/develop/ui/compose/notifications/create-notification#Updating)
