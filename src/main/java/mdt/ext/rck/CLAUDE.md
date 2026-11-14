# mdt.ext.rck

Runs one RCK press-process simulation per MDT operation call: a WebSocket session with the external RCK simulator, modelled as a `utils.websocket.WebSocketStateChart`, with live progress mirrored into the output arguments of the operation's own simulation submodel. User guide (Korean): [README.md](README.md). Built on [utils.statechart](../../../../../../../../common/utils/src/main/java/utils/statechart/CLAUDE.md) and [utils.websocket](../../../../../../../../common/utils/src/main/java/utils/websocket/CLAUDE.md) — read those for signal queueing, termination and fall-back semantics.

## Files

- **`RCKSimulationOperation`** — FA³ST `OperationProvider` entry point (`AbstractJavaOperationProvider`). Referenced by class name from model configs, so **do not rename it**. Inputs: `LayoutName` (required) and the optional `EquipmentPropertiesList` (`xs:string`, `facility`-message JSON; missing/blank means `"{}"`), parsed by `parseEquipmentPropertiesList` before the running flag is taken (malformed → `IllegalArgumentException`, no connection) and always passed to `Builder.overrideEquipmentProperties` (an empty list changes nothing).
- **`LoadEquipmentPropertiesList`** — FA³ST `OperationProvider` (`AbstractJavaOperationProvider`) that wraps `Builder.loadEquipmentProperties()`: input `LayoutName`, output `EquipmentPropertiesList` (`xs:string`, `RCKEquipmentPropertiesList.toJsonNode()` as pretty JSON). Referenced by class name from model configs. One request at a time per operation (`m_running`); a concurrent call throws `IllegalStateException`.
- **`RCKSimulation`** — the state chart (all states are private inner classes). Created only through `RCKSimulation.builder()` (`serverUrl`/`clientId`/`processName`/`layoutName` required; `observer`, `responseTimeout`, `stopResponseTimeout`, `keepAlive(ping, pong)`, `progressFormat`, `overrideEquipmentProperties`); the constructors and `setResponseTimeout`/`setStopResponseTimeout` are package-private for tests. Public API: `builder()`, `start()`, `requestStop()`, `cancel(boolean)`, `getSimulationResult()`.
- **`RCKSimulationContext`** — `WebSocketContext` subtype: immutable identity (clientId, processName, layoutName) + mutable results (`equipmentProperties`, `latestProgress`, `videoUrl`; volatile, setters package-private). Constructor package-private (the builder makes it); callers only read it via `getContext()`. No `equals`/`hashCode` (the chart compares contexts by identity).
- **`RCKSimulationProgress`** — record for one `simulation_status` report; `parse(JsonNode)`.
- **`RCKSimulationResult`** — record for a finished run: `Termination` (`COMPLETED`/`STOPPED`/`FAILED`/`CANCELLED`, each with `code()` and `displayName()`), `stopConfirmed`, final progress, `videoUrl` (the `video_info` download URL, nullable), failure cause.
- **`SimulationObserver`** — progress callbacks (`onPhaseChanged(RCKSimulationPhase)`, `onProgressUpdated`, `onVideoReady`, `onFinished(RCKSimulationResult)`); `NONE` does nothing.
- **`RCKSimulationPhase`** — enum of the non-terminal states (`CONNECTING`, `REGISTERING`, `LOADING_LAYOUT`, `STARTING`, `RUNNING`, `STOPPING`) with `displayName()`; the chart maps state paths to it (`STATE_PHASES`). Internal state paths are not part of the API.
- **`MDTSimulationOutputReporter`** — writes progress to MDT operation arguments asynchronously; one per operation instance, `newRunObserver()` per call.
- **`RCKExecutors`** (package-private) — RCK-only daemon threads: one timer thread + a cached worker pool.

## Call flow

`invokeAsync` (FA³ST's path) → `bindOutputs` (validate output variables before starting) → `startSimulation`: take the run slot (`m_running`), `RCKSimulation.builder()...observer(createObserver()).build()` where `createObserver()` = `m_outputReporter.newRunObserver()`, schedule the `maxDuration` cancel on `RCKExecutors`, register `whenFinishedAsync`, `sim.start()`, return. On finish: decide success/failure and write outputs (inside one `try`), then `awaitOutputFlush()` (reporter `flushAsync()`, ≤ 5 s), then call **exactly one** callback; callback exceptions are only logged. `invokeSync` shares `startSimulation` and blocks; an interrupt cancels the simulation.

- Failure wrapping in the async path mirrors the default `OperationProvider.invokeAsync` (`toCallbackFailure`): unwrap, then wrap only `TimeoutException`/`TaskException`/`CancellationException`/`ExecutionException`/`InterruptedException` in `ExecutionException`.
- Output variables are all optional. `ElementValues.update(null, …)` rejects a `null` target (`IllegalArgumentException("SubmodelElement is null")` since 2026-11-01; it used to be an NPE), so `OperationOutputBindings.update` skips `null` targets.
- `maxDuration` (default 1 h) is measured from `sim.start()`; MDT output preparation is not included (it happens on the writer thread).

## State chart (`RCKSimulation`)

```
/ConnectingWebSocket → /Registering → /LayoutLoading → /SimulationStarting → /Running → /Completed
                       (register)      (layout)          (command)             (video_info)
any of Registering..Running + STOP_REQUESTED → /Stopping → /Stopped
errors → /Failed
```

- **`/ConnectingWebSocket`** is utils `States.WebSocketConnectingState`, subclassed anonymously only to report itself to the observer on `enter()`. It has no response timer (the WebSocket connect timeout covers it).
- **`ResponseWaitingState`** (base of everything from Registering to Stopping) extends utils `AbstractTimedState` (scheduler `RCKExecutors.TIMER`, signal executor `RCKExecutors.WORKERS`). The base class arms the timer on `enter()`, cancels it on `exit()`, and turns its own current `TimedOut` into `onTimeout()` (stale ones → `stay()`); timer and generation are per state object. `enter()`/`exit()` are `final`: override `onEnter()`/`onExit()` and call `super.onEnter()` first (it reports the state to the observer). `getTimeout()` = `m_responseTimeout` (`Stopping`: `m_stopResponseTimeout`). Other signals go to the utils `AbstractState.on(...)` transition table.
- **`JsonMessageState`** (Registering, LayoutLoading, SimulationStarting, Running): handlers per JSON `type` via `onMessageType(type, transition|MessageHandler)`; `sendRequest()` hook runs at the end of `onEnter()`. Any text message calls `restartTimeout()` (deliberate: any message means the simulator is alive). Unregistered types → warn once per state/type, then DEBUG, and `stay()`. Common table entries: `STOP_REQUESTED` → Stopping; `SendFailed`, handler exception, response timeout, `ConnectionClosed` (any code), `WebSocketError` → Failed.
- **`Running`**: `simulation_status` → parse, store, `onProgressUpdated` (a malformed report is warned once, then DEBUG, and ignored); `video_progress`/`video_ready` are logged raw (not deserialized: a format change must not fail the run); `video_info` → store, `onVideoReady(url)` if the URL is present, then Completed (also without a URL; `videoUrl()` is then `null`). The message is read through the private record `VideoInfo(downloadUrl)`; only the URL is exposed (context `getVideoUrl()`, `RCKSimulationResult.videoUrl()`).
- There is no video-receiving state: the run completes at `video_info`; binary messages are not expected (only `Stopping` handles `BinaryMessage`, with `stay()`).
- **`Stopping`** sends the stop command; `simulation_stop` → Stopped with `m_stopConfirmed = true`; stop timeout (default 10 s), `ConnectionClosed`, `WebSocketError` → Stopped (unconfirmed); stop send failure → Failed.
- **Terminal states** record `m_finalTermination`. `Completed` and `Failed` send the stop command (≤ 2 s) **before** the base class closes the WebSocket; `Stopped` already sent it.
- **Failure cause** is set only through `failWith(cause)` = `Transitions.fail(STATE_FAILED, …)`; there is no cause in the context.
- **`requestStop()`**: `processSignal(STOP_REQUESTED)`; `IGNORED` (not started / cancelling) → `cancel(true)`. While connecting, the unhandled STOP is turned into `super.cancel(true)` by `onUnhandledSignal` (no connection, so no stop command).
- **`cancel(boolean)`** sends the stop command first when running (connection still open), then delegates. `onUnhandledSignal` calls `super.cancel` on purpose to skip that.

## Result and observer

- `notifyFinished` is the **first** `whenFinished` listener (registered in the constructor), so it builds `m_result` before any other listener runs; `getSimulationResult()` throws ISE until then. It must not call `getCurrentState()`: a pre-start cancel runs finish listeners holding only the execution lock, and `getCurrentState()` would take the chart lock (inverted lock order). It reads `m_finalTermination` instead.
- `getSimulationResult()` is not named `getResult()` because `Execution.getResult()` already exists.
- Terminal states are reported only through `onFinished`, not `onPhaseChanged`.
- Observer methods run under the chart lock (or, for a pre-start cancel's `onFinished`, the execution lock): they must return quickly. `notifyObserver` swallows `Exception`s.
- **`State`**: `State` (MDT argument) = display names for humans; never branch on display text. The operation has no result-code output: failures surface as exceptions, and `Termination.code()` is available only on `RCKSimulationResult`.

## MDT output reporting (`MDTSimulationOutputReporter`)

- **One reporter per operation instance**, shared by all runs. Per-run reporters let a late write of the previous run ("완료") overwrite the next run's "소켓 연결중" (external review P1). A single drain task writes everything in order (an in-flight request included); `newRunObserver()` bumps the run number, clears pending updates and enqueues the reset values; updates from an older run's observer are dropped.
- Pending updates are coalesced per argument (latest value wins). `flushAsync()` completes when everything enqueued so far has been attempted.
- MDT manager creation (`HttpMDTManager.connectWithDefault()` may connect to **MQTT synchronously** when the client config has `mqttEndpoint`), instance-id lookup and argument activation all run on the writer thread, never in `invokeAsync` or a constructor. Arguments that fail to resolve are retried once per later run.
- `drainPendingUpdates` resets `m_writerActive` in `finally` and reschedules if work remains, so an `Error` in a write does not stall the reporter forever.
- `toUtilizationJson` is the single formatter for `EquipmentUtilizationList` (a STRING property) used by both the reporter and the operation outputs: an array of `{group, equipment, index, utilization}` in message order.
- **Target submodel**: the reporter takes a `Supplier<MDTSubmodelReference>` and builds `MDTArgumentReference.newInstance(smRef, OUTPUT, arg)` per argument (no expression parsing). `RCKSimulationOperation.toOperationSubmodel(opRef, …)` uses the first `SUBMODEL` key of the operation reference → `DefaultSubmodelReference.ofId(id)` (activation finds the instance by submodel id); only without one (tests) it falls back to `ofIdShort(instanceId, DEFAULT_SUBMODEL_ID_SHORT = "PressSimulation")`, instance id from `MDTService.getInstanceConfig().getId()` or `MDT_INSTANCE_ID`; neither → `null`, nothing is reported.

## Threads and sends

- `StateChart` runs a signal on the **sending thread** when the chart is idle. Never post signals directly from shared threads (utils `Executions.getExecutor()`, common `ForkJoinPool`, JDK `HttpClient` threads): the transition (stop-send wait, chart lock) would run there. Use `RCKExecutors.schedule(...)` (or a timed state built on `RCKExecutors.TIMER` + `WORKERS`) for timers and `postSignalAsync(...)` for future callbacks.
- The timer is a `ScheduledThreadPoolExecutor` with `setRemoveOnCancelPolicy(true)`: the response timer is re-armed on every message, and cancelled tasks would otherwise sit in the queue until their 2-minute delay.
- **JDK `WebSocket` accepts one outstanding text send**; a send started before the previous completes fails its future with `IllegalStateException("Send pending")` (utils does not serialize). Every RCK send therefore goes through `sendJson`, which chains on `m_sendTail` (a failed send does not block the next). Without it, a stop right after the start command, or `cancel()`'s stop from another thread, could fail and turn a requested stop into Failed.

## Equipment-only run

`Builder.loadEquipmentProperties()` runs the chart only up to the `facility` message and blocks until it ends: the private `m_equipmentOnly` flag makes `LayoutLoading` go to `Completed` instead of `SimulationStarting`, and `trySendStopCommand` does nothing under the flag, so no stop command goes out on any ending (completed, failed, cancelled). The flag is reachable only through this method, so callers never hold the chart (no `requestStop()`, no `RCKSimulationResult`). The builder's observer is not used, so nothing is reported as a completed simulation. Failures are `ExecutionException` with the chart's cause; an interrupt cancels the run. A stop command targets the process name and could stop another simulation running on the same process, which is why it is not sent; the effect of the layout request itself on a running simulation is unverified.

## Parsing rules

- `MAPPER` ignores unknown JSON properties.
- `RCKSimulationProgress.parse(node[, Format])`: `Progress` must be present and numeric; the production field (`Format.productionPath`, default `Utilizations.Conveyor_6.Production`) defaults to 0 only when absent, non-numeric is an error; `Utilizations` is an object whose field names are equipment groups and whose values are one equipment object or an array of them; an equipment with a text `Name`, an int-range integer `Index` and a numeric `Utilization` becomes one `RCKEquipment(group, name, index)` → `Float` entry, others are skipped, and a repeated equipment keeps the last value; a missing field means an empty map, a non-object is an error; `ProcessName` is ignored; `utilizations()` is an unmodifiable map in message order. The format (`Format(productionPath)`) comes from the builder (`progressFormat`) and, for the operation, from the optional config arg `productionPath` (`.`-separated; empty items fail configuration).
- `RCKEquipmentPropertiesList.parse(node)` is the only parser of the `facility` message's `message` (`{group: object | [objects]}`): it must be a JSON object (`{}` means no equipments), each equipment needs a string `Name` and an `int` `Index`, every other property must be numeric, and a repeated equipment (group, `Name`, `Index`) is an error. `toElementCollectionValue(proto)` gives the `group → Index<n> → properties` form of `misc/properties.json` (keys like `Index1`, because a bare number is not a valid AAS idShort; no `Name`; two equipments with the same group and `Index` but different `Name`s throw `IllegalStateException`). All failures are the checked `JacksonDeserializationException`, so a `facility` message without `message` or with a malformed one goes to `Failed` through the handler-exception path. `SimulationStarting` sends `toJsonNode()` as the start command's `message`, so what the simulator gets back is normalized: a one-element array group becomes an object and an empty array group disappears.
- Equipment overrides: `Builder.overrideEquipmentProperties(list)` is applied in `LayoutLoading` right after parsing, through the package-private `RCKEquipmentPropertiesList.overriddenBy(overrides, logger)`, which returns a new list. An equipment matches only when group, `Name` and `Index` are all equal. Only values of properties the simulator sent are replaced; an equipment or property absent from the simulator's list is logged at WARN and ignored (never added). Order follows the simulator's list. The overridden list is what the context holds, so it is both the start command's `message` and the result of `loadEquipmentProperties()`.

## Open items

- **`video_info` without a URL completes the run** with `videoUrl() == null` — intended, not a failure.
- One operation runs one simulation at a time (`RCKSimulationOperation.m_running`): a call while one runs throws `IllegalStateException` before anything starts (all runs share the output arguments and the `clientId`). The slot is released in the finish listener **before** `onFinished`, so a result callback may invoke the operation again; a start that fails before the chart runs releases it in `startSimulation`.

## Tests

`src/test/java/mdt/ext/rck/` (JUnit 4, OkHttp `MockWebServer` with `FakeRckServer`):
- `RCKSimulationTest` — protocol, stop/cancel ordering (stop before close), timeouts, connection errors, finish reporting, send serialization.
- `LoadEquipmentPropertiesListTest` — arguments, JSON output, no start/stop commands, failures, concurrent call.
- `RCKSimulationOperationTest` — arguments, outputs, async return, `maxDuration`, callback-once, failure wrapping. Tests override `createObserver()` to return `SimulationObserver.NONE`.
- `MDTSimulationOutputReporterTest` — coalescing, cross-run ordering, `flushAsync`, lazy/retried resolution, `Error` recovery (Mockito mocks of `MDTArgumentReference`).
- `RCKSimulationProgressTest` — parsing rules.
- `RCKSimulationManualRun` — manual run against the real simulator (has `main()`, not a test).

Run with `gradle :test --tests 'mdt.ext.rck.*'` from `mdt-instance`. The leading `:` matters: without it Gradle also applies the filter to the included `mdt-client` build, which fails with "No tests found". Several tests are timing-based (elapsed-time bounds, sleeps). `back_to_back_sends_are_serialized` sends four 8 MB messages: smaller payloads fit in the socket buffer and do not reproduce "Send pending", and one message over 16 MB makes OkHttp close the connection.
