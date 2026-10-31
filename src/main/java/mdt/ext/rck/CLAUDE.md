# mdt.ext.rck

Runs one RCK press-process simulation per MDT operation call: a WebSocket session with the external RCK simulator, modelled as a `utils.websocket.WebSocketStateChart`, with live progress mirrored into the instance's `PressSimulation` operation arguments. User guide (Korean): [README.md](README.md). Built on [utils.statechart](../../../../../../../../common/utils/src/main/java/utils/statechart/CLAUDE.md) and [utils.websocket](../../../../../../../../common/utils/src/main/java/utils/websocket/CLAUDE.md) — read those for signal queueing, termination and fall-back semantics.

## Files

- **`RCKSimulationOperation`** — FA³ST `OperationProvider` entry point (`AbstractJavaOperationProvider`). Referenced by class name from model configs, so **do not rename it**.
- **`RCKSimulation`** — the state chart (all states are private inner classes). Public API: `start()`, `requestStop()`, `cancel(boolean)`, `setResponseTimeout`, `setStopResponseTimeout`, `getSimulationResult()`.
- **`RCKSimulationContext`** — `WebSocketContext` subtype: immutable identity (clientId, processName, layoutName) + mutable results (`facility`, `latestProgress`, `videoInfo`). No `equals`/`hashCode` (the chart compares contexts by identity).
- **`RCKSimulationProgress`** — record for one `simulation_status` report; `parse(JsonNode)`.
- **`RCKSimulationResult`** — record for a finished run: `Termination` (`COMPLETED`/`STOPPED`/`FAILED`/`CANCELLED`, each with `code()` and `displayName()`), `stopConfirmed`, final progress, `VideoInfo`, failure cause.
- **`SimulationObserver`** — progress callbacks (`onStateChanged`, `onProgressUpdated`, `onVideoReady`, `onFinished(RCKSimulationResult)`); `NONE` does nothing.
- **`MDTSimulationOutputReporter`** — writes progress to MDT operation arguments asynchronously; one per operation instance, `startRun()` per call.
- **`RCKExecutors`** (package-private) — RCK-only daemon threads: one timer thread + a cached worker pool.
- **`VideoInfo`** — record for the `video_info` message.

## Call flow

`invokeAsync` (FA³ST's path) → `bindOutputs` (validate output variables before starting) → `startSimulation`: build context, `new RCKSimulation(ctx, createObserver())` where `createObserver()` = `m_outputReporter.startRun()`, schedule the `maxDuration` cancel on `RCKExecutors`, register `whenFinishedAsync`, `sim.start()`, return. On finish: decide success/failure and write outputs (inside one `try`), then `awaitOutputFlush()` (reporter `flushAsync()`, ≤ 5 s), then call **exactly one** callback; callback exceptions are only logged. `invokeSync` shares `startSimulation` and blocks; an interrupt cancels the simulation.

- Failure wrapping in the async path mirrors the default `OperationProvider.invokeAsync` (`toCallbackFailure`): unwrap, then wrap only `TimeoutException`/`TaskException`/`CancellationException`/`ExecutionException`/`InterruptedException` in `ExecutionException`.
- Output variables are all optional. `ElementValues.update(null, …)` throws NPE (its final `else` calls `sme.getClass()`), so `OperationOutputBindings.update` skips `null` targets.
- `maxDuration` (default 1 h) is measured from `sim.start()`; MDT output preparation is not included (it happens on the writer thread).

## State chart (`RCKSimulation`)

```
/ConnectingWebSocket → /Registering → /LayoutLoading → /SimulationStarting → /Running → /ReceivingVideo → /Completed
                       (register)      (layout)          (command)             (video_info)   (count bytes)
any of Registering..ReceivingVideo + STOP_REQUESTED → /Stopping → /Stopped
errors → /Failed
```

- **`/ConnectingWebSocket`** is utils `States.WebSocketConnectingState`, subclassed anonymously only to report itself to the observer on `enter()`. It has no response timer (the WebSocket connect timeout covers it).
- **`ResponseWaitingState`** (base of everything from Registering to Stopping): `enter()` reports the state and arms the response timer; `exit()` cancels it; a current `ResponseTimedOut` → `onResponseTimeout()`, a stale one (older generation) → `stay()`. Other signals go to the utils `AbstractState.on(...)` transition table.
- **`JsonMessageState`** (Registering, LayoutLoading, SimulationStarting, Running): handlers per JSON `type` via `onMessage(type, transition|handler)`; `sendRequest()` hook runs after `enter()`. Any text message restarts the timer (deliberate: any message means the simulator is alive). Unregistered types → warn once per state/type, then DEBUG, and `stay()`. Common table entries: `STOP_REQUESTED` → Stopping; `SendFailed`, handler exception, response timeout, `ConnectionClosed` (any code), `WebSocketError` → Failed.
- **`Running`**: `simulation_status` → parse, store, `onProgressUpdated` (a malformed report is warned once, then DEBUG, and ignored); `video_progress`/`video_ready` are logged raw (not deserialized: a format change must not fail the run); `video_info` (sent once) → store, `onVideoReady(url)`, then ReceivingVideo, or Completed if `fileSize <= 0`.
- **`ReceivingVideo`** counts bytes only (nothing is saved; the result is the download URL). Completed when the size is reached, and also on `ConnectionClosed`, `WebSocketError` or response timeout (the URL is already known). Keeping the byte stream instead of completing at `video_info` was a user decision.
- **`Stopping`** sends the stop command; `simulation_stop` → Stopped with `m_stopConfirmed = true`; stop timeout (default 10 s), `ConnectionClosed`, `WebSocketError` → Stopped (unconfirmed); stop send failure → Failed.
- **Terminal states** record `m_finalTermination`. `Completed` and `Failed` send the stop command (≤ 2 s) **before** the base class closes the WebSocket; `Stopped` already sent it.
- **Failure cause** is set only through `failWith(cause)` = `Transitions.fail(STATE_FAILED, …)`; there is no cause in the context.
- **`requestStop()`**: `processSignal(STOP_REQUESTED)`; `IGNORED` (not started / cancelling) → `cancel(true)`. While connecting, the unhandled STOP is turned into `super.cancel(true)` by `onUnhandledSignal` (no connection, so no stop command).
- **`cancel(boolean)`** sends the stop command first when running (connection still open), then delegates. `onUnhandledSignal` calls `super.cancel` on purpose to skip that.

## Result and observer

- `notifyFinished` is the **first** `whenFinished` listener (registered in the constructor), so it builds `m_result` before any other listener runs; `getSimulationResult()` throws ISE until then. It must not call `getCurrentState()`: a pre-start cancel runs finish listeners holding only the execution lock, and `getCurrentState()` would take the chart lock (inverted lock order). It reads `m_finalTermination` instead.
- `getSimulationResult()` is not named `getResult()` because `Execution.getResult()` already exists.
- Terminal states are reported only through `onFinished`, not `onStateChanged`.
- Observer methods run under the chart lock (or, for a pre-start cancel's `onFinished`, the execution lock): they must return quickly. `notifyObserver` swallows `Exception`s.
- **`FinalState` vs `State`**: `FinalState` (operation output) = `Termination.code()` for machines; `State` (MDT argument) = display names for humans; never branch on display text.

## MDT output reporting (`MDTSimulationOutputReporter`)

- **One reporter per operation instance**, shared by all runs. Per-run reporters let a late write of the previous run ("완료") overwrite the next run's "소켓 연결중" (external review P1). A single drain task writes everything in order (an in-flight request included); `startRun()` bumps the run number, clears pending updates and enqueues the reset values; updates from an older run's observer are dropped.
- Pending updates are coalesced per argument (latest value wins). `flushAsync()` completes when everything enqueued so far has been attempted.
- MDT manager creation (`HttpMDTManager.connectWithDefault()` may connect to **MQTT synchronously** when the client config has `mqttEndpoint`), instance-id lookup and argument activation all run on the writer thread, never in `invokeAsync` or a constructor. Arguments that fail to resolve are retried once per later run.
- `drainPendingUpdates` resets `m_writerActive` in `finally` and reschedules if work remains, so an `Error` in a write does not stall the reporter forever.
- `toUtilizationJson` is the single formatter for `AverageUtilization` (a STRING property) used by both the reporter and the operation outputs.
- Instance id: `MDTService.getInstanceConfig().getId()`, falling back to `MDT_INSTANCE_ID` (no service context, e.g. tests).

## Threads and sends

- `StateChart` runs a signal on the **sending thread** when the chart is idle. Never post signals directly from shared threads (utils `Executions.getExecutor()`, common `ForkJoinPool`, JDK `HttpClient` threads): the transition (stop-send wait, chart lock) would run there. Use `RCKExecutors.schedule(...)` for timers and `postSignalAsync(...)` for future callbacks.
- The timer is a `ScheduledThreadPoolExecutor` with `setRemoveOnCancelPolicy(true)`: the response timer is re-armed on every message, and cancelled tasks would otherwise sit in the queue until their 2-minute delay.
- **JDK `WebSocket` accepts one outstanding text send**; a send started before the previous completes fails its future with `IllegalStateException("Send pending")` (utils does not serialize). Every RCK send therefore goes through `sendJson`, which chains on `m_sendTail` (a failed send does not block the next). Without it, a stop right after the start command, or `cancel()`'s stop from another thread, could fail and turn a requested stop into Failed.

## Parsing rules

- `MAPPER` ignores unknown JSON properties.
- `RCKSimulationProgress.parse`: `Progress` must be present and numeric; `Conveyor_6.Production` defaults to 0 only when absent, non-numeric is an error; equipment without `Name` or with a non-numeric `Utilization` is excluded; averages per type in `SUPPORTED_EQUIPMENT_TYPES` (prefix match), rounded to 3 decimals; the map is a sorted unmodifiable `TreeMap`.

## Open items

- **`video_info` validation is pending a policy decision** (external review #3): `status` and `downloadUrl` are not checked, a missing or negative `fileSize` is treated like 0 (→ Completed), and a `null` URL is reported. Needed: the simulator's success `status` value, and whether a missing URL should fail the run. `onVideoReady` keeps its name until then.
- All concurrent calls of one operation register with the same `clientId` (testing convenience; the simulator should get one per call).
- `PressSimulation`, `Conveyor_6` and the equipment-type list are hard-coded domain rules.

## Tests

`src/test/java/mdt/ext/rck/` (JUnit 4, OkHttp `MockWebServer` with `FakeRckServer`):
- `RCKSimulationTest` — protocol, stop/cancel ordering (stop before close), timeouts, connection errors, finish reporting, send serialization.
- `RCKSimulationOperationTest` — arguments, outputs, async return, `maxDuration`, callback-once, failure wrapping. Tests override `createObserver()` to return `SimulationObserver.NONE`.
- `MDTSimulationOutputReporterTest` — coalescing, cross-run ordering, `flushAsync`, lazy/retried resolution, `Error` recovery (Mockito mocks of `MDTArgumentReference`).
- `RCKSimulationProgressTest` — parsing rules.
- `RCKSimulationManualRun` — manual run against the real simulator (has `main()`, not a test).

Run with `gradle :test --tests 'mdt.ext.rck.*'` from `mdt-instance`. The leading `:` matters: without it Gradle also applies the filter to the included `mdt-client` build, which fails with "No tests found". Several tests are timing-based (elapsed-time bounds, sleeps). `back_to_back_sends_are_serialized` sends four 8 MB messages: smaller payloads fit in the socket buffer and do not reproduce "Send pending", and one message over 16 MB makes OkHttp close the connection.
