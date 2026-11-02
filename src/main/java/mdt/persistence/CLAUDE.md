# mdt.persistence

The instance's FA³ST `Persistence`, built as a **stack of layers**: each layer wraps a base `Persistence`, handles the requests it cares about and delegates the rest. External data reaches AAS elements through this stack (not through AssetConnection). User guides (Korean): [asset/README.md](asset/README.md) and [timeseries/README.md](timeseries/README.md) — they document config formats, request-by-request behaviour and diagnostics in detail; read the relevant one before changing a subpackage and update it when behaviour changes.

## Layers and how the stack is built

`MDTInstanceMain.loadPersistenceConfig()` builds the config chain inside-out, so the runtime order (outermost first) is:

```
persistenceStacks[0] … [n-1]      only {"@type":"concurrent"} → ConcurrentPersistence (index 0 is outermost)
  → TimeSeriesPersistenceStack    only if config.timeSeriesSubmodels is non-empty
    → AssertVariableBasedPersistence   only if config.assetVariables is non-empty
      → PersistenceInMemory       FA³ST, loaded from model.json
```

- **`PersistenceStack<C>`** — abstract base. Every `Persistence` method delegates to `getBasePersistence()`; subclasses override only what they intercept. `init()` creates the base **reflectively from the base config's class name** (`name.substring(0, name.lastIndexOf('C'))`, i.e. `FooConfig` → `Foo` in the same package), then calls its `init`. `start()`/`stop()` cascade to the base.
- **`PersistenceStackConfig<P>`** — holds the base config (`basePersistence`). Its setter forces `initialModelFile = model.json` (CWD-relative) when the base is a `PersistenceInMemoryConfig`; the normal path builds the chain in code and sets the real model file, so this only matters for JSON-deserialized configs.
- **`MDTPersistenceStackConfig`** — the `@type` union for `persistenceStacks` entries. Only `concurrent` is registered; the `timeseries`/`asset`/`mqtt` subtypes are commented out because those layers are configured through their own `config.json` fields, not through `persistenceStacks`.
- **Finding a layer**: never cast the top-level persistence — which layer is outermost depends on config. Use `PersistenceStack.findInStack(top, Type.class)` (walks base links; `FOption.empty()` if absent). The time-series operation providers do this.
- **Reading through the top**: code that must see outer layers' effects (e.g. the time-series operations reading `Metadata`) reads via `((MDTService)serviceContext).getPersistence()`, not via its own base.

## Root classes

- **`MDTModelLookup`** — JVM singleton built once from the **initial** `model.json` (`getInstanceOrCreate` — first call wins; `getInstance()` throws ISE before that). Indexes Submodels by idShort and id, finds the Data Submodel (may be absent → `getDataSubmodel()` returns `null`, `isDataSubmodel()` false), and maps each parameter member path (`DataInfo.{Equipment|Operation}.{…}ParameterValues[i]`) to its `ParameterID`. It is **not** updated when the model changes at runtime; asset variables and element locations resolved at start keep their original paths/prototypes.
- **`ConcurrentPersistence`** — serializes access with one `utils.thread.Guard`, but **only** for: `getSubmodelElement`/`update` whose path is exactly a parameter member path, `getSubmodel`/`save`/`deleteSubmodel` of the Data Submodel, and every `insert`. Everything else (including sub-paths below a parameter member such as `…ParameterValues[0].ParameterValue`) passes through unlocked. Deleting a parameter element throws `UnsupportedOperationException`.
- **`MDTPersistenceException`** — `MDTException` subtype; currently unused (asset code throws `AssetVariableException`, the stack layers throw FA³ST's `PersistenceException`).

## Subpackages

- **`asset/`** — `AssertVariableBasedPersistence` (sic, `Assert`) maps elements to external sources via `AssetVariable`s (`jdbc/`, `http/`, `mqtt/`, `opcua/`, `robot/`). Key rules (details in its README):
  - Variables are grouped by Submodel **id**; read lookup checks them **in config order** and returns the first one equal to or containing the request path (no most-specific-wins). Path comparison treats `.` and `[` as separators.
  - Writes go to **external sources first, then the base model**; no transaction, no rollback. A failed external write leaves earlier writes in place and skips the base save.
  - `read()` must return a copy the caller may mutate; `update()` must not keep or mutate its argument. Shared caches (`m_prototype`) are guarded and copied.
  - `isReadable`/`isUpdatable` describe the external link only; a read-only variable does not make the AAS element read-only.
  - Implementations are found reflectively: `XxxConfig` → `Xxx` in the same package with an accessible `Xxx(XxxConfig)` constructor. `@type` → config class is an explicit `switch` in `AssetVariableConfigs.parseJsonNode()` — add new types there.
  - `close()` may run before `initialize()` finished and more than once. MQTT variables return their shared `MqttServicePool` lease there; OPC UA variables currently do not close their connection.
- **`timeseries/`** — `TimeSeriesPersistenceStack` serves TimeSeries Submodels from JDBC tables: `Metadata` from the base model, `Segments.FullRange` (count/min/max summary) and optional `Segments.Tail` built per request; `ReadRecords`/`ReadRecordsByRange` operation providers read records through `JdbcRecordsReader`. It is read-only for registered Submodels (`insert`/`update`/deletes throw `UnsupportedOperationException`; `save`/`deleteAll` still reach the base). Start fails unless config entries and time-series Submodels match by idShort; operations look configs up by Submodel **id**.

## Extending

- **New stack layer**: subclass `PersistenceStack<C>` with a `C extends PersistenceStackConfig<…>` named `<Layer>Config` in the same package (the reflective base creation depends on that naming), add a `Core` subtype of `MDTPersistenceStackConfig` with `@JsonSubTypes`, and a branch in `MDTInstanceMain.loadPersistenceConfig()`.
- **New asset variable type**: follow "새 데이터소스 구현 방법" in [asset/README.md](asset/README.md) (config with `SERIALIZATION_TYPE` `mdt:asset:<kind>:<variant>`, registration in `AssetVariableConfigs`, same-package implementation). JDBC variants extend `AbstractJdbcAssetVariable`: fill the buffer passed to `loadOnBuffer`, never `m_prototype`; `saveBuffer` must not commit/rollback or swap the cache.

## Tests

`src/test/java/mdt/persistence/asset/` (JUnit 4, Mockito): `AssertVariableBasedPersistenceTest`, `ConnectionConfigRefTest`, the JDBC tests (`JdbcAssetVariable{Mapping,Review,Consistency}Test`, `JdbcColumnBindingTest`), `MqttAssetVariableTest`, `RobotStateVariableTest`. There are no tests for the root classes or `timeseries/`. Run with `gradle :test --tests 'mdt.persistence.*'` (the leading `:` keeps the filter off the included builds).

## Gotchas

- Layer order is fixed by `loadPersistenceConfig()`: `persistenceStacks` can only add layers **outside** the time-series and asset layers.
- `ConcurrentPersistence` does not protect asset-variable I/O or time-series queries in general — only the paths listed above.
- `MDTModelLookup` is a static singleton: tests that need a different model must account for the first-call-wins initialization.
- `AssertVariableBasedPersistence` / `AssertVariableBasedPersistenceConfig` are misspelled in code (see the project [CLAUDE.md](../../../../../CLAUDE.md)); keep the names unless renaming deliberately across repos.
