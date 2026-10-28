# mdt.endpoint.companion 검토 결과

`mdt.endpoint.companion` 패키지(`ProgramCompanion`, `ProgramCompanionConfig`)의 검토 결과와 처리 현황이다.
항목을 처리할 때마다 아래 현황표의 상태와 반영 commit을 갱신한다.

- 검토 기준: 2026년 10월 28일 mdt-instance `master` (`117594a`)
- 함께 검토한 코드: [MDTInstanceMain](../../src/main/java/mdt/MDTInstanceMain.java)의 companion 로더,
  utils의 [ProgramService](../../../../common/utils/src/main/java/utils/async/command/ProgramService.java)·
  [ServiceShutdownHook](../../../../common/utils/src/main/java/utils/async/command/ServiceShutdownHook.java),
  실제 모델 설정(Welder, inspector)
- 관련 문서: [endpoint-guide.md §7](../endpoint-guide.md#7-companion-외부-프로그램)

상태 표기: ⬜ 미반영 · 🔄 진행 중 · ✅ 완료 · ⏸ 보류(사유 기재) · ➖ 해당 없음(재검토 결과 문제 아님)

## 현황표

| ID | 구분 | 중요도 | 제목 | 관련 | 상태 | 반영 commit |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 문제 | 중 | 사용자가 적은 `environments`가 조용히 사라짐 | B | ✅ 완료 | 211f843 |
| 2 | 문제 | 중 | 동반 프로그램이 실패해도 인스턴스는 정상으로 보임 | C | ✅ 완료 | 808f6dd |
| 3 | 문제 | 하 | `stop()`이 종료를 기다리지 않고 종료 hook부터 해제 | D | ✅ 완료 | 211f843 |
| 4 | 문제 | 하 | 동반 프로그램을 하나만 둘 수 있음 | A | ✅ 완료 | (commit 전) |
| 5 | 문제 | 하 | `programConfig`가 null일 때 원인을 알기 어려운 NPE | | ✅ 완료 | d68d22a |
| 6 | 문제 | 하 | hook 이름 형식과 문서의 정리 범위 설명이 실제와 다름 | | ✅ 완료 | d68d22a |
| 7 | 문제 | 중 | 인스턴스 시작 실패 시 `main()`이 JVM을 종료하지 않음 | | ➖ 해당 없음 | |
| A | 제안 | | 여러 동반 프로그램 지원 (`companions` 맵) | 4 | ✅ 완료 | (commit 전) |
| B | 제안 | | 인스턴스 변수와 사용자 `environments` 병합 | 1 | ✅ 완료 | 211f843 |
| C | 제안 | | 필수 동반 프로그램 지정(`required`)과 실패 로그 | 2 | ✅ 완료 | 808f6dd |
| D | 제안 | | `stop()`에서 종료 대기 | 3 | ✅ 완료 | 211f843 |

권장 처리 순서: **1+B → 3+D, 5, 6 → 2+C → 4+A** (#7은 해당 없음)

## 문제점

### 1. 사용자가 적은 `environments`가 조용히 사라짐

- **위치**: [MDTInstanceMain.java:551](../../src/main/java/mdt/MDTInstanceMain.java#L551) (패키지 밖의 로더)
- **내용**:
  - 로더가 `companionConfig.setEnvironments(m_instanceEnvVars)`로 맵을 통째로 교체한다.
  - 설정의 `"environments": {...}`는 오류나 경고 없이 무시된다.
  - 자식 프로세스에는 인스턴스 변수 5개(`MDT_INSTANCE_ID`, `MDT_INSTANCE_ENDPOINT`, `MDT_URL`, `MDT_INSTANCE_PORT`, `MDT_INSTANCE_DIR`)만 전달된다.
- **영향**:
  - endpoint-guide에 "병합하지 않고 교체한다"고 적혀 있지만, 설정 필드가 있는데 쓰이지 않는 것은 함정이다.
  - 현재 `~/mdt`의 모델 설정 중 `environments`를 쓰는 곳은 없다.
- **수정 방향**: 제안 B

### 2. 동반 프로그램이 실패해도 인스턴스는 정상으로 보임

- **위치**: [ProgramCompanion.java `start()`](../../src/main/java/mdt/endpoint/companion/ProgramCompanion.java)
- **내용**:
  - `start()`는 `startAsync()`만 부르고 바로 돌아온다.
  - 프로그램이 시작되지 못하거나(`startTimeout`), 재시작 한도(`restartLimit`)를 넘으면 `ProgramService`는 `FAILED`가 된다.
  - 이때 `ProgramService`가 WARN·ERROR 로그를 남기지만, 인스턴스는 계속 실행되고 Manager에도 정상으로 보인다.
- **영향**: 로그의 logger가 `utils.async.command.ProgramService`라서, 어느 인스턴스의 동반 프로그램인지 알기 어렵다.
- **수정 방향**: 제안 C

### 3. `stop()`이 종료를 기다리지 않고 종료 hook부터 해제

- **위치**: [ProgramCompanion.java `stop()`](../../src/main/java/mdt/endpoint/companion/ProgramCompanion.java)
- **내용**: `stopAsync()`를 요청한 직후 JVM 종료 hook을 해제한다. 반면 hook 쪽은 `stopAsync()` 후 최대 3초 기다리므로, 두 경로의 동작이 서로 다르다.
- **영향**:
  - `stop()` 직후 JVM이 끝나면 자식 프로세스 정리가 끝나기 전에 내려갈 수 있다. 이때는 hook도 이미 없으므로 자식이 남을 수 있다.
  - 다만 지금 mdt-instance에는 FA³ST 서비스의 `stop()`을 부르는 종료 경로가 없어, 실제 영향은 테스트나 내장 실행으로 한정된다.
- **수정 방향**: 제안 D

### 4. 동반 프로그램을 하나만 둘 수 있음

- **위치**: [ServiceEndpointConfigs](../../src/main/java/mdt/config/ServiceEndpointConfigs.java)의 `companion` (`ProgramServiceConfig` 하나)
- **근거**:
  - Welder 모델의 `companion/` 디렉터리에 실행 설정이 두 개(`program_inspect_waveform.json`, `program_predict_total_quantity.json`) 있다.
  - 둘째 프로그램은 `sbin/predict_total_quantity.sh`로 손으로 띄우고 있다.
- **수정 방향**: 제안 A

### 5. `programConfig`가 null일 때 원인을 알기 어려운 NPE

- **위치**: [ProgramCompanionConfig.java `toString()`](../../src/main/java/mdt/endpoint/companion/ProgramCompanionConfig.java), `ProgramCompanion.init()`
- **내용**: `programConfig`가 null이면 `toString()`과 `init()`에서 NPE가 난다.
- **영향**: 지금 로더는 항상 값을 넣으므로 실제로 생길 가능성은 낮다.
- **수정 방향**: `init()`에서 명확한 메시지로 검사하고, `toString()`은 null을 허용한다.

### 6. hook 이름 형식과 문서의 정리 범위 설명이 실제와 다름

- **위치**: `ProgramCompanion.init()`, 클래스 Javadoc
- **내용**:
  - 종료 hook 이름이 `<instanceId>Companion`처럼 그냥 이어 붙여진다(예: `robot-01Companion`).
  - Javadoc은 "FA³ST 서비스가 비정상 종료되더라도 자식 프로세스가 정리된다"고 설명한다. 실제로 정리되는 것은 JVM이 종료 hook을 실행하는 경우(정상 종료, SIGTERM, `System.exit`)뿐이다.
  - `kill -9`나 JVM 충돌에서는 hook이 돌지 않아 자식이 남는다. 프로세스 그룹 단위 정리는 없다.
- **수정 방향**: hook 이름을 `<instanceId>-companion` 형식으로 바꾸고, Javadoc과 endpoint-guide에 정리 범위의 한계를 적는다.

### 7. 인스턴스 시작 실패 시 `main()`이 JVM을 종료하지 않음 — 재검토 결과 문제 아님

- **위치**: [MDTInstanceMain.java](../../src/main/java/mdt/MDTInstanceMain.java)의 `main()`, `runService()`
- **처음 판단**: `runService()`가 다시 던진 예외를 `main()`이 출력만 하고 `System.exit`을 하지 않아, 이미 시작된 엔드포인트(HTTP 등)의 쓰레드 때문에 JVM이 남을 수 있다고 보았다. 코드만 읽고 내린 판단이었다.
- **재검토 결과**:
  - `main()`이 부르는 `app.run()`은 utils의 [HomeDirPicocliCommand](../../../../common/utils/src/main/java/utils/HomeDirPicocliCommand.java)`.run()`이다. 이 메서드가 `run(homeDir)`에서 나온 `Exception`을 잡아 `failed: ...`를 출력하고 `System.exit(-1)`을 한다.
  - `runService()`가 던지는 `RuntimeException`도 이 경로를 탄다. 따라서 엔드포인트 시작이 실패하면(companion `required` 포함) 종료 hook이 실행된 뒤 JVM이 종료 코드 255로 끝난다.
  - 같은 경로(`run(homeDir)`의 예외)를 설정 파일이 없는 홈 디렉터리로 실행해 확인했다: `failed: java.nio.file.NoSuchFileException: .../config.json`, 종료 코드 255.
  - 엔드포인트 시작 실패 자체를 실제 인스턴스로 재현하지는 않았다.
  - Manager(`JarInstanceExecutor`)는 표준 출력에서 `ERROR`를 sentinel로 찾으므로, `runService()`의 ERROR 로그로 시작 실패를 알아챈다.
- **남는 작은 틈**: `run(homeDir)`에서 `Exception`이 아닌 `Error`(예: `NoClassDefFoundError`)가 나면, `HomeDirPicocliCommand`를 지나 `main()`의 `catch (Throwable)`에 걸린다. 여기서는 usage만 출력하고 종료하지 않는다. 드문 경우라 따로 고치지 않았다.
- 2026-10-28 #2 처리 중 발견하여 별도 항목으로 분리했고, 같은 날 재검토 후 종결.

## 인터페이스 제안

### A. 여러 동반 프로그램 지원 (#4)

이름을 키로 쓰는 맵을 받는다. 이름은 hook 이름과 로그에 쓴다.

```json
"serviceEndpoints": {
  "companions": {
    "inspect-waveform": { "commandLine": ["python3", "inspect_waveform.py"], "workingDirectory": "welder" },
    "predict-quantity": { "commandLine": ["java", "-cp", "...", "welder.PredictTotalQuantity"], "workingDirectory": "companion" }
  }
}
```

- 기존 단일 `companion`도 계속 받는다.
- **정한 사항**(2026-10-28):
  - `companion`과 `companions`를 함께 쓸 수 있다.
  - 단일 `companion`은 이름이 없다(hook 이름 `<instanceId>-companion`). `companions` 항목은 `<instanceId>-companion-<name>`이므로 겹치지 않는다.

### B. 인스턴스 변수와 사용자 `environments` 병합 (#1)

- 인스턴스 변수를 기본으로 깔고, 사용자의 `environments`를 그 위에 얹는다. 같은 이름이면 사용자 값이 우선한다.
- 사용자가 `MDT_*` 변수를 덮어쓰면 경고 로그를 남긴다.
- endpoint-guide의 "병합하지 않고 교체" 설명을 바꾼다.

### C. 필수 동반 프로그램 지정과 실패 로그 (#2)

- 선택 필드 `required`를 추가한다.
  - `true`: `start()`에서 프로그램이 RUNNING이 될 때까지(`startTimeout`) 기다리고, 실패하면 인스턴스 시작도 실패시킨다.
  - `false`(기본값 후보): 지금처럼 비동기로 시작한다.
- `required` 값과 관계없이, 서비스가 `FAILED`가 되면 companion에서 동반 프로그램 이름과 인스턴스 ID를 담은 ERROR 로그를 남긴다.
- **정한 사항**(2026-10-28): `required` 기본값은 `false`. `required`여도 시작 이후의 실패는 ERROR 로그만 남긴다.

### D. `stop()`에서 종료 대기 (#3)

- `stop()`도 hook과 같은 시간(3초)까지 서비스 종료를 기다린 뒤 hook을 해제한다.
- 대안: 서비스가 TERMINATED가 되는 순간(리스너) hook을 해제한다.

## 처리 기록

처리한 항목은 아래에 날짜, 항목, commit, 요약을 덧붙인다.

| 날짜 | 항목 | commit | 요약 |
| --- | --- | --- | --- |
| 2026-10-28 | 1, B | 211f843 | `ProgramCompanion.mergeEnvironments()` 추가. 로더가 인스턴스 변수에 사용자 `environments`를 병합(사용자 값 우선, 덮어쓰면 변수 이름만 경고). 테스트 `ProgramCompanionTest` 5개, endpoint-guide §7 갱신 |
| 2026-10-28 | 3, D | 211f843 | `stop()`이 hook과 같은 시간(3초)까지 종료를 기다린 뒤 hook 해제. 시간 안에 끝나지 않으면 WARN 로그를 남기고 hook을 유지해 JVM 종료 시 다시 정리. hook 등록에도 같은 대기 시간을 명시. 테스트 `ProgramCompanionStopTest` 3개(실제 자식 프로세스 사용), endpoint-guide §7 갱신 |
| 2026-10-28 | 5 | d68d22a | `init()`이 `config`·`programConfig`가 없으면 NPE 대신 `IllegalArgumentException`("companion programConfig is not set")을 던지고, 검사가 상태 변경보다 먼저 일어나 실패 후 재초기화 가능. `ProgramCompanionConfig.toString()`은 null을 허용. 테스트 `ProgramCompanionInitTest` 3개 |
| 2026-10-28 | 6 | d68d22a | hook 이름을 `<instanceId>-companion`(없으면 `companion`)으로 변경(`toHookName()`). 클래스 Javadoc과 endpoint-guide §7에 hook이 정리하는 범위(정상 종료·`System.exit`·SIGTERM 등)와 한계(`kill -9`·JVM 충돌, 프로세스 그룹 정리 없음)를 기재. 테스트 `testHookName` 추가 |
| 2026-10-28 | 2, C (1단계) | 808f6dd | 서비스 FAILED 시 `ProgramCompanion` logger로 ERROR 로그(이름·직전 상태·원인·명령). `ProgramService` logger를 `mdt.endpoint.companion.ProgramCompanion.<hook 이름>`으로 지정. 테스트 `ProgramCompanionFailureTest` 3개, endpoint-guide §7 갱신. 2단계(`required`, 시작 실패 시 `main()` 종료 처리)는 정할 사항이 남아 미반영 |
| 2026-10-28 | 2, C (2단계) | 808f6dd | `CompanionConfig extends ProgramServiceConfig`에 `required`(기본 `false`) 추가, `ServiceEndpointConfigs.companion` 타입 변경. `required`이면 `start()`가 `startTimeout`+1초까지 RUNNING을 기다리고 실패 시 `EndpointException`. 시작 이후 실패는 1단계의 ERROR 로그만. 테스트 `ProgramCompanionRequiredTest` 4개, endpoint-guide §7 갱신. `main()` 종료 처리는 #7로 분리 |
| 2026-10-28 | 7 | — | 재검토 결과 문제 아님. `HomeDirPicocliCommand.run()`이 `run(homeDir)`의 예외에 `System.exit(-1)`을 하므로 시작 실패 시 JVM이 종료된다(빈 홈 디렉터리로 실행해 종료 코드 255 확인). 코드 변경 없음 |
| 2026-10-28 | 4, A | (commit 전) | `serviceEndpoints.companions`(이름→`CompanionConfig`, 작성 순서 유지) 추가. 단일 `companion`과 함께 사용 가능하며 단일 항목은 이름 없음. 프로그램마다 `ProgramCompanionConfig`(새 `name` 필드, 영문자·숫자·`_`·`-`)를 만들어 독립 실행. hook·logger 이름은 `<instanceId>-companion-<name>`. 로더 조립을 `ProgramCompanionConfig.from()`으로 이동. 테스트 `ProgramCompanionMultipleTest` 4개, endpoint-guide §7 갱신 |
