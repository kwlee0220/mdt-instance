# mdt (root package)

Entry point plus the shared building blocks the subpackages depend on: element-location expressions, FA³ST-request-based element access, global-config lookup, connection-config references, the shared MQTT connection pool, and the RobotTwin connection DTO. The user guide is [README.md](README.md) (Korean); startup and config formats are in the [project README](../../../../README.md) §5–6 and the [project CLAUDE.md](../../../../CLAUDE.md). Update the package README when behaviour here changes.

## Classes

- **`MDTInstanceMain`** — picocli entry point (`HomeDirPicocliCommand`). Turns `config.json` into a FA³ST `ServiceConfig` (`loadPersistenceConfig` / `loadEndpointConfigs` / `loadAssetConnectionConfigs`), dumps it to `config_faaast.json` in the CWD, starts `MDTService`. In jar mode it prints `[***MARKER***] MDTInstance started: <endpoint>`; mdt-manager scans stdout for this exact string — never change it. Startup order and value precedence are documented in the project CLAUDE.md; keep that and this file consistent.
- **`ElementLocation`** / **`ElementLocations.parseStringExpr`** — location expressions used by asset variables, MQTT subscriptions, etc. Three forms, chosen by the first `:`-separated token:
  - `<submodelIdShort>:<idShortPath>` → `DefaultElementLocation` (exactly two parts, else `IllegalArgumentException`).
  - `param:<expr>` → `MDTParameterLocation`. `expr` is an integer index, or a `ParameterID` optionally followed by a sub-path starting at the first `.` (else first `[`); resolved by `SubmodelUtils.resolveParameterValueElementPath` against the Data submodel's `…ParameterValues` list.
  - `oparg:<smIdShort>:<INPUT|OUTPUT>:<name>` → `MDTOperationArgumentLocation`; the submodel's semantic ID must be AI or Simulation.
  Locations must be `activate(MDTModelLookup)`-ed before `getSubmodelId()`/`getElementPath()` (they throw `IllegalStateException("not activated")`; `DefaultElementLocation.getElementPath()` is the exception — it returns the parsed path). Activation only resolves the submodel (and the parameter index); it does not check that the element path exists.
- **`FaaastRuntime`** — runs element reads/writes as FA³ST requests through `ServiceContext.execute()`, so they pass through the whole persistence stack like external REST calls. Obtain with `getOrCreate(serviceContext)`: a static singleton that is **replaced** when called with a different `ServiceContext` (tests rely on this). `execute()` never throws for request failures — it returns an error-status `Response`:
  - write methods (`putSubmodelElementByPath`, `setSubmodelElementValueByPath`, `updateSubmodelElementValue`, and `ElementValueUpdater.update`) check the status via `checkSuccess` and throw `ElementUpdateException` (carries the FA³ST `StatusCode` and messages);
  - read methods (`getSubmodelElement*`) return `getPayload()` without checking, so a failure yields `null`.
  `newValueUpdater(loc)` looks up the element's `TypeInfo` once and fails fast (`ElementUpdateException`) if the element is missing; the returned updater reuses that type info and takes AAS value-only JSON. Used by `endpoint.mqtt.MqttEndpoint`.
- **`ElementValueUpdater`**, **`ElementUpdateException`** — the updater interface and its failure (`RuntimeException`; `getStatusCode()` is `null` when the failure happened before a request was sent).
- **`MDTGlobalConfigurations`** — static lookup over `mdt_global_config.json`; the file is set once by `MDTInstanceMain` via `setGlobalConfigFile`. Groups `jdbcs`, `mqttBrokers`, `opcuas`, `robotTwins` (+ `persistence`/`default` for `loadJpaConfiguration`, used only by `ksx9101`). Lookup: exact name, else for `default` (case-insensitive) the group's first entry, else `ResourceNotFoundException`. **The file is re-read on every call** — resolve once at init and keep the result. The chosen entry is passed through `utils.StrSubstitutor` with no user map: `${env:X}`/`${sys:X}` work, an unprefixed `${X}` throws `IllegalArgumentException` (undefined variable). If no file was configured, every lookup throws `IllegalStateException`.
- **`ConnectionConfigRef<T>`** — record for "global-config name **or** inline object" connection fields (`jdbcConfig`, `mqttBrokerConfig`, `robotTwinConfig`, …). Exactly one of `name`/`inline` is non-null (checked in the canonical constructor). `load(jnode, field, legacyNameField, cls)`: string → name, object → inline, missing/`null` → `default`; the legacy `*ConfigName` field is accepted but not together with the new field (`IOException`). `serialize()` writes the original form and drops `null` fields of inline objects (some config setters reject `null`, e.g. `JdbcConfiguration.setDriverClassName`). `toDisplayString()` is for logs — the caller's inline formatter must not print secrets.
- **`MqttServicePool`** — shares one `MqttService` per `BrokerKey` (broker URL, connection timeout, keep-alive interval, reconnect interval). `acquire()` returns a `Lease`; the service is created and `startAsync()`-ed on first acquire (connection is asynchronous — not guaranteed connected on return) and stopped (≤5s wait) when the last lease closes. `Lease.close()` is idempotent. Rules for callers: never start/stop the shared service; close your own `Subscription`s (from `MqttService.subscribe`) before closing the lease. A FAILED service is logged and replaced on the next `acquire()`; existing lease holders keep the failed instance. The map is guarded by `MqttServicePool.class`; stopping happens outside the lock.
- **`RobotTwinConfig`** — DTO `robotTwinBaseUrl`, `twinId`; API paths are `<base>/api/v1/robot_twins/<twinId>/...`.
- **`MDTServiceConfigUtils`** — builds a default FA³ST `ServiceConfig`; currently unreferenced (as is `FaaastRuntime.getExecutor()`).

## Conventions

- Element access from instance code goes through `FaaastRuntime`, not the base `Persistence`, so outer persistence layers are applied.
- New connection fields should use `ConnectionConfigRef` and a `MDTGlobalConfigurations` group constant rather than ad-hoc name/object parsing.
- New users of MQTT go through `MqttServicePool`; do not construct `MqttService` directly in instance code.
- `ElementLocations` and the location classes still import Guava's `com.google.common.base.Preconditions`; prefer `utils.Preconditions` in new or touched code (project rule).

## Gotchas

- The location parser splits on `:` with no escaping; idShorts/paths containing `:` cannot be addressed.
- `oparg:` ignores `<name>`: the path is built only from the kind (`AIInfo.Inputs.InputValue`, `SimulationInfo.Outputs.OutputValue`), with no list index. Missing parts (`oparg:Sim:INPUT`) give `ArrayIndexOutOfBoundsException`; a lowercase kind gives `IllegalArgumentException` from `MDTArgumentKind.valueOf`. The package guides (`persistence/asset`, `endpoint`) already document this limitation — keep them in sync if it is fixed.
- `param:` needs a Data submodel; a missing `ParameterID` throws `IllegalArgumentException` ("Failed to find SMC of ParameterID=…") at activation.
- In `MDTInstanceMain`, the global-config branch for `MDT_GLOBAL_CONFIG_FILE` logs the home-directory path variable instead of the env path (cosmetic log bug).
- `MDTGlobalConfigurations` and `MqttServicePool` are JVM-wide static state; tests that touch them must reset or isolate (see `MqttServicePoolTest`).

## Tests

`src/test/java/mdt/` (JUnit 4): `FaaastRuntimeValueUpdaterTest`, `FaaastRuntimePersistenceFailureTest` (error responses → `ElementUpdateException`, reads through the stack), `MqttServicePoolTest`. Run with `gradle :test --tests 'mdt.FaaastRuntime*' --tests 'mdt.MqttServicePoolTest' --rerun -x :utils:test -x :mdt-client:test`.
