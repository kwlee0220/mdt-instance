# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`mdt-instance` is the server process that runs a single MDT (Manufacturing Digital Twin) instance. It loads one AAS Environment (`model.json`), serves it over the AAS REST API v3.0 (HTTPS only), binds model elements to external data sources, and dispatches AAS Operation calls to Java / external-program / Python-script / remote-HTTP implementations. It is built on Fraunhofer IOSB **FA³ST Service 1.3.0** (`de.fraunhofer.iosb.ilt.faaast.service:starter`): MDT features are plugged into FA³ST's extension points (Persistence, AssetConnection, Endpoint) rather than wrapping it. Java 21, group `etri`, root package `mdt`, entry point `mdt.MDTInstanceMain`.

Instances are normally launched and supervised by `mdt-manager` (`../mdt-manager`, `mdt.exector.jar.JarInstanceExecutor`), but can also run standalone and register themselves with the manager. User-facing documentation is [README.md](README.md) (Korean) plus the package guides under [docs/](docs/).

## Common commands

There is no Gradle wrapper — use the system `gradle`.

```bash
MDT_BUILD_VERSION=1.0.0 gradle shadowJar   # build/libs/mdt-instance-<version>-all.jar ("unknown" if unset)
gradle compileJava                         # quickest check after edits
gradle test                                # all tests
gradle test --tests 'mdt.assetconnection.operation.robot.*'
gradle eclipse
```

`settings.gradle` pulls sibling projects in as source: `:utils` → `../../common/utils`, `:mdt-client` → `../mdt-client`, `:mdt-robot-client` → `../mdt-robot-client`. All three must be checked out next to this repo, and changes there (e.g. `utils.async.command` API changes) break this build directly. `docker/build_image.sh` hard-codes `mdt-instance-1.0.0-all.jar`, so Docker builds need `MDT_BUILD_VERSION=1.0.0`.

Tests are **JUnit 4** (`org.junit.Test`, run through the vintage engine) with Mockito 3. They currently cover the robot-twin operation providers (`src/test/java/mdt/assetconnection/operation/robot/`, sharing `RobotTwinProviderTestSupport`) and `RobotStateVariable`. Do not introduce Jupiter-only APIs without adding the Jupiter engine.

## Startup wiring (`MDTInstanceMain`)

`MDTInstanceMain` (picocli, extends `utils.HomeDirPicocliCommand`; home dir = `--home` or the CWD) turns `config.json` into a FA³ST `ServiceConfig`:

1. Read `config.json` and substitute `${env:…}` / `${sys:…}` (commons-text interpolator).
2. Resolve id (`--id` → `MDT_INSTANCE_ID` → `id`), instance endpoint (`--instanceEndpoint` → `MDT_INSTANCE_ENDPOINT` → `instanceEndpoint` → `https://${MDT_HOST:-localhost}:<port>/api/v3.0`), and the manager URL. **`MDT_URL` is mandatory and always overwrites the config's `mdtUrl`** — there is no CLI option for it.
3. Substitute again with the instance variables `MDT_INSTANCE_ID`, `MDT_INSTANCE_ENDPOINT`, `MDT_URL`, `MDT_INSTANCE_PORT`, `MDT_INSTANCE_DIR`, then re-parse into `MDTInstanceConfig`. The same map is passed as the environment of the companion program.
4. Resolve the global config file and the PKCS12 keystore (alias `server-key`); a missing keystore password fails startup.
5. Load `model.json` into the `MDTModelLookup` singleton (idShort → Submodel index used by every element-location expression).
6. Build the `ServiceConfig`: `loadPersistenceConfig`, `loadEndpointConfigs`, `loadAssetConnectionConfigs`. The result is dumped to `config_faaast.json` in the CWD for debugging.
7. Start `MDTService` (a FA³ST `Service` subclass). In `--type jar` mode, print `[***MARKER***] MDTInstance started: <endpoint>` — **mdt-manager scans stdout for this exact marker** to detect a successful start; do not change or remove it.

The HTTPS port is taken from the instance endpoint URL, not from `--port`; `--port` only matters when no endpoint is given.

## Architecture

- **`mdt.config`** — `MDTInstanceConfig` (the `config.json` DTO), `ServiceEndpointConfigs` (`mqtt`/`ros2`/`robotTwin`/`companion`), `OperationsConfig` + sealed `AASOperationConfig` (`programs`/`javas`/`scripts`/`https`), `MDTService`/`MDTServiceConfig`.
- **`mdt.persistence`** — persistence *stack*: each layer wraps a base `Persistence`. Order built by `loadPersistenceConfig()`, outermost first: `persistenceStacks` entries (only `{"@type":"concurrent"}` → `ConcurrentPersistence`, a lock that serializes requests; index 0 is outermost) → `TimeSeriesPersistenceStack` (if `timeSeriesSubmodels`) → `AssertVariableBasedPersistence` (if `assetVariables`) → FA³ST `PersistenceInMemory` holding `model.json`. Subpackages `asset/` (jdbc, http, mqtt, opcua, robot) and `timeseries/`. Code that must see the effect of outer layers reads through the top-level persistence, not its own base (see `ReadRecordsOperation`).
- **`mdt.assetconnection`** — a single `MDTAssetConnection` whose `MDTOperationProvider` dispatches each Operation reference to a Java / Program / Script / HTTP provider. Value and subscription providers are intentionally `Unsupported*` — element values flow through the persistence stack, not through AssetConnection.
- **`mdt.endpoint`** — FA³ST `Endpoint`s used as lifecycle hooks, not only HTTP servers: `MDTManagerHealthMonitor` (jar mode; `System.exit(0)` on the first failed `/health` call), `reconnector/` (external mode; periodic `POST /instance-manager/registry/<id>`), `mqtt/`, `ros2/` (rosbridge), `rt/` (robot twin; polling loop currently disabled), `companion/` (wraps `utils.async.command.ProgramService`). The former `audit/` package (periodic JDBC logging, never registered by the default loader) was removed.
- **Root `mdt.*`** — `ElementLocation(s)` parse location expressions: `<submodelIdShort>:<path>`, `param:<id>`, `oparg:<smIdShort>:<INPUT|OUTPUT>:<name>`; `activate(MDTModelLookup)` resolves them at start. `FaaastRuntime` executes element reads/writes as FA³ST requests so they pass through the persistence stack. `MqttServicePool` shares one `MqttService` per resolved broker config (URL, timeouts, reconnect interval) between MQTT asset variables and `MqttEndpoint`; callers hold a `Lease`, must unsubscribe their own topics before closing it (`MqttService.subscribe` returns a `Subscription` handle whose `close()` does that once), and must never start/stop the shared service directly. `MqttService` keeps its subscriber map under a lock but never waits for the broker while holding it (publish and broker subscribe/unsubscribe run outside the lock; subscribe/unsubscribe are serialized per service by a separate broker lock and wait at most the operation timeout, 10s by default; reconnect resubscription runs off the Paho callback thread) — holding the lock while waiting for SUBACK/UNSUBACK deadlocked against `dispatch()` once the Paho inbound queue filled. Topic filters are validated before registration. `ConnectionConfigRef` parses the "name or inline object" connection field shared by asset variables and robot operation providers (`jdbcConfig`, `mqttBrokerConfig`, `robotTwinConfig`; legacy `*ConfigName` accepted but not together with the new field). `MDTGlobalConfigurations` reads named connection configs from `mdt_global_config.json` (groups `jdbcs`, `mqttBrokers`, `opcuas`, `rosBridges`, `robotTwins`; falls back to `default`, then to the group's first entry).
- **`mdt.ksx9101`** — KS X 9101 manufacturing-data JPA mapping (`KSX9101Persistence`); not wired by `MDTInstanceMain`.
- **`mdt.ext`** — project-specific operation providers (e.g. `ext/rck`, RCK press simulation over WebSocket; see its README).

Detailed behaviour, config formats, and known limitations are in [docs/endpoint-guide.md](docs/endpoint-guide.md), [docs/assetconnection-guide.md](docs/assetconnection-guide.md), [docs/robot-twin-operation-guide.md](docs/robot-twin-operation-guide.md), [docs/asset-persistence-guide.md](docs/asset-persistence-guide.md), and [docs/timeseries-persistence-guide.md](docs/timeseries-persistence-guide.md). Read the relevant guide before changing a package, and update it when behaviour changes.

## Extension points

- **New Java operation**: implement `mdt.assetconnection.operation.OperationProvider` with a public constructor `(ServiceContext, Reference, JavaOperationProviderConfig)` — `MDTOperationProvider` instantiates it reflectively by `className`. Implement `invokeSync`; the default `invokeAsync` runs it on `CompletableFuture.runAsync`.
- **New asset variable type**: add a config class with a `SERIALIZATION_TYPE` constant (`mdt:asset:<kind>:<variant>`) and register it in the `switch` in `persistence/asset/AssetVariableConfigs` — `@type` values are not discovered automatically.
- **New persistence layer**: add a `MDTPersistenceStackConfig` subtype (`@JsonSubTypes` on that interface) and a branch in `MDTInstanceMain.loadPersistenceConfig()`.
- **New service endpoint**: add a field to `ServiceEndpointConfigs` and a branch in `MDTInstanceMain.loadEndpointConfigs()`.
- **New config field**: add it to `MDTInstanceConfig` and document it in README §6.

## Conventions

- Source comments and Javadoc are Korean; match surrounding density. User-facing docs (`README.md`, `docs/*.md`) are written in natural Korean technical prose.
- Fields use the `m_` prefix with Lombok `@Accessors(prefix="m_")`; static loggers are `s_logger`. Author tag `@author Kang-Woo Lee (ETRI)`.
- Prefer `utils.Preconditions` (`checkNotNullArgument`, `checkArgument`, `checkState`) over Guava `Preconditions` in new or touched code; older files (including `MDTInstanceMain`) still use Guava.
- Durations in config are strings parsed by `utils.UnitUtils` (`"10s"`, `"1m"`).
- `commons-logging` is excluded globally.

## Gotchas

- `MDTService` reads FA³ST's private fields `persistence`, `fileStorage`, `config` by reflection. A FA³ST upgrade that renames them breaks this at runtime, not compile time.
- Files ending in `.javax` (`config/ServiceConfigAugmentor.javax`, `assetconnection/UnsupportedOperationProvider.javax`) are disabled code kept for reference; they are not compiled.
- `AssertVariableBasedPersistence` / `AssertVariableBasedPersistenceConfig` are misspelled (`Assert` for `Asset`) in code; keep the existing names unless doing a deliberate rename across repos.
- Element-location prefixes are Submodel **idShorts**, not global IDs, and the parser splits on `:` with no escaping.
- `logs/` and `config_faaast.json` are written to the CWD, not the `--home` directory.
- `mdt_instance_config.yaml` at the repo root is not read by any code.
- `mdt_global_config.json` and `mdt_cert.p12` contain secrets; never commit them.
