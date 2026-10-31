# `mdt.ext.rck` 검토 항목

- 대상: `src/main/java/mdt/ext/rck/` 전체(8개 파일)와 `src/test/java/mdt/ext/rck/RCKSimulationTest.java`
- 기준 커밋: `ae6f84a` (2026-10-31). 1차 작업(#1~#5, #22, #23, #27 및 함께 해결된 항목)은 `9083280`. 2차 작업(나머지, #18 제외)은 `33240dd`. #18은 2026-10-31, 미커밋.
- 상태 값: `대기` · `진행` · `완료` · `보류`(결정 필요) · `기각`
- 항목을 처리하면 요약표의 상태를 바꾸고, 해당 항목의 **결과**에 커밋과 처리 내용을 적는다.
- 1차 작업 항목의 결과에 적힌 "미커밋"은 `9083280`으로 커밋되었다.

## 요약

| # | 분류 | 심각도 | 제목 | 상태 |
|---|------|--------|------|------|
| 1 | 버그 | 높음 | 영상 정보가 없으면 오퍼레이션이 NPE로 실패 | 완료 |
| 2 | 버그 | 높음 | 수신한 영상 첨부가 항상 실패(문자열 Property에 첨부) | 완료 |
| 3 | 버그 | 높음 | `AverageUtilization` 타입이 두 곳에서 다름 → MDT 반영 전체가 꺼질 수 있음 | 완료 |
| 4 | 버그 | 높음 | 응답 대기 시간 제한이 없어 오퍼레이션이 끝나지 않을 수 있음 | 완료 |
| 5 | 버그 | 중간 | 모르는 JSON 필드 하나로 시뮬레이션이 `Failed` | 완료 |
| 6 | 버그 | 중간 | `invokeSync`가 인터럽트되어도 시뮬레이션은 계속 돈다 | 완료 |
| 7 | 버그 | 중간 | `Running`이 받은 `video_info`를 버리고 다시 기다린다 | 완료 |
| 8 | 버그 | 중간 | 시뮬레이션 중 서버가 정상 close하면 `CANCELLED`로 끝난다 | 완료 |
| 9 | 버그 | 중간 | 영상 파일을 작업 디렉터리에 서버가 준 이름으로 저장 | 완료 |
| 10 | 버그 | 낮음 | `WaitingVideo.exit()`가 영상 정보 없이도 URL 갱신을 시도 | 완료 |
| 11 | 버그 | 낮음 | 인스턴스 ID를 `MDT_INSTANCE_ID` 환경변수에서만 얻음 | 완료 |
| 12 | 버그 | 낮음 | `Stopping`의 응답 대기 타이머가 취소되지 않음 | 완료 |
| 13 | 버그 | 낮음 | 이용률을 `asInt()`로 읽어 소수점이 잘림 | 완료 |
| 14 | 버그 | 낮음 | `LayoutName` 오류에 잘못된 예외 타입과 메시지 | 완료 |
| 15 | 문서 | 낮음 | README가 코드와 다름 | 완료 |
| 16 | 문서 | 낮음 | `WaitingVideo` Javadoc 복사 오류, 없는 메소드 참조 | 완료 |
| 17 | 정리 | 낮음 | MDT `State` 표시 이름 오타 | 완료 |
| 18 | 정리 | 낮음 | `FinalState`(경로)와 `State`(한글 이름) 값 형식 불일치 | 완료 |
| 19 | 정리 | 낮음 | 로깅 불일치(`System.out`, `s_logger`/`getLogger()`) | 완료 |
| 20 | 정리 | 낮음 | 운영 코드 안의 개발용 `main()` | 완료 |
| 21 | 정리 | 낮음 | `RCKSimulationContext.equals/hashCode` 불필요 | 완료 |
| 22 | 인터페이스 | — | MDT 반영을 `RCKSimulation`에서 분리 | 완료 |
| 23 | 인터페이스 | — | 메시지 `type` 디스패치를 공통 기반으로 | 완료 |
| 24 | 인터페이스 | — | 실패 원인을 `FailureState`/`Transitions.fail`로 일원화 | 완료 |
| 25 | 인터페이스 | — | `RCKSimulationResult`를 record로 | 완료 |
| 26 | 인터페이스 | — | 오퍼레이션 설정(ping/pong/대기 시간)을 인자로 | 완료 |
| 27 | 이름 | — | 클래스·메소드·필드 이름 변경 | 완료 |
| 28 | 테스트 | — | 테스트 공백 | 완료 |

권장 순서: 1 → 2 → 3 → 4 → 5 (오퍼레이션 결과가 틀리거나 끝나지 않는 문제), 그다음 22 → 23 (구조 변경이 6~12의 수정 위치를 바꾼다), 이름(27)은 구조 변경과 함께.

---

## 버그

### #1 영상 정보가 없으면 오퍼레이션이 NPE로 실패 — 높음

- 위치: [RCKSimulationOperation.java:102](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L102)
- 문제: `ctx.getSimulationVideoInfo().downloadUrl()`는 영상 정보가 `null`이면 NPE를 던진다. 다음 경우 모두 `null`이다.
  - 영상 수신이 중간에 끊김 — `ReceivingVideo.exit()`가 일부러 `null`로 되돌린다([RCKSimulation.java:680](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L680)).
  - `WaitingVideo`에서 연결이 끊겨 `Completed` — 영상 정보를 받기 전이다.
  - `requestStop()`으로 `/Cancelled` — 차트 결과가 `COMPLETED`라 `sim.get()`이 정상 반환한다.
  
  시뮬레이션은 성공했는데 진척도·생산량까지 기록되지 않고 오퍼레이션 전체가 실패한다. 개발용 `main()`([RCKSimulation.java:1034](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L1034))도 같다.
- 제안: 영상이 없으면 `SimulationVideoUrl`을 빈 문자열로 두고 나머지 출력은 기록한다. `/Cancelled`로 끝난 경우를 오퍼레이션이 어떻게 보고할지(정상 반환 / 예외)도 정한다.
- 결과: 완료(미커밋). 영상 정보가 없으면 `SimulationVideoUrl`을 빈 문자열로 기록하고 나머지 출력은 그대로 기록한다. `/Stopped`로 끝나도 정상 반환한다(`FinalState`로 구분).

### #2 수신한 영상 첨부가 항상 실패 — 높음

- 위치: [RCKSimulation.java:664-672](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L664-L672), [RCKSimulation.java:902](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L902)
- 문제: `m_videoRef`는 `SimulationVideoUrl`(문자열 Property) 인자인데 `ReceivingVideo.exit()`가 `m_videoRef.updateAttachment(m_videoFile)`를 부른다. `ElementReference.updateAttachment(File)`은 대상이 `FileValue`가 아니면 `IOException`을 던지므로, 영상을 다 받아도 매번 ERROR 로그만 남는다. 같은 인자에는 `WaitingVideo.exit()`가 이미 다운로드 URL을 썼다.
- 제안: 영상 파일이 MDT에 필요한지 먼저 정한다.
  - URL만 필요하면 `ReceivingVideo`의 파일 저장과 첨부를 없애고, `video_info` 수신으로 끝낸다(#7, #9도 같이 해결).
  - 파일이 필요하면 별도의 File 인자(예: `SimulationVideo`)를 두고 그쪽에 첨부한다.
- 결과: 완료(미커밋). 답변("URL만 있으면 됨")에 따라 파일 저장과 첨부를 없앴다. `video_info`를 받는 순간 `onVideoReady(url)`로 URL을 반영한다. `ReceivingVideo`는 받은 바이트 수만 센다(프로토콜 진행을 위해 수신은 유지).

### #3 `AverageUtilization` 타입 불일치 — 높음 (모델 확인 필요)

- 위치: [RCKSimulationOperation.java:79](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L79), [RCKSimulationOperation.java:99-100](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L99-L100), [SimulationOutputUpdater.java:47-50](../../src/main/java/mdt/ext/rck/SimulationOutputUpdater.java#L47-L50)
- 문제: 오퍼레이션은 `AverageUtilization`을 STRING Property로 읽어 JSON 문자열을 쓰고, `SimulationOutputUpdater`는 같은 인자에 `ElementCollectionValue`(SMC)를 쓴다. 둘 중 하나는 반드시 실패한다.
  - 모델이 SMC면 `getPropertyVariable`이 IAE를 던져 오퍼레이션이 시작하자마자 실패한다.
  - 모델이 Property면 `connectMdtInstanceManager()` 안의 `update(RCKSimulationResult.empty())`([RCKSimulation.java:899](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L899))가 실패한다. 그러면 catch에서 `m_mdtConnected = false`가 되어 `State`, 진척도, 영상 URL까지 **모든 MDT 중간 반영이 경고 한 줄과 함께 꺼진다**.
- 제안: 실제 모델의 `PressSimulation` 출력 인자 타입을 확인하고 한쪽으로 맞춘다. 초기화 실패가 MDT 반영 전체를 끄지 않도록 인자별로 실패를 분리하는 것도 검토한다(#22).
- 결과: 완료(미커밋). 답변(STRING Property)에 따라 `MDTSimulationOutputs`도 JSON 문자열을 쓴다. 형식은 `MDTSimulationOutputs.toUtilizationJson()` 하나로 오퍼레이션과 공유한다. 인자를 하나씩 준비·갱신하므로 한 인자의 실패가 나머지를 끄지 않는다.

### #4 응답 대기 시간 제한 없음 — 높음

- 위치: `Connecting`, `LayoutLoading`, `SimulationStarting`, `WaitingVideo`, `ReceivingVideo` 상태; [RCKSimulationOperation.java:92](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L92)
- 문제: 시뮬레이터가 연결은 유지하면서(ping/pong 정상) 응답을 보내지 않으면 차트는 그 상태에 영원히 머물고, `sim.get()`도 끝나지 않는다. 오퍼레이션 호출이 FA³ST 스레드 하나를 무기한 붙잡는다.
- 제안: 두 단계로 둔다.
  - 상태별 응답 대기 시간: `Stopping`과 같은 방식으로 진입 시 타이머 신호를 걸고, 시간이 지나면 `Failed`로 전이한다.
  - 전체 상한: `sim.get(timeout)` 또는 `sim.setTimeout(...)`.
  
  값은 #26의 설정 인자로 받는다.
- 결과: 완료(미커밋). `WaitingState` 기반 클래스가 진입 시 응답 대기 타이머(기본 2분, `setResponseTimeout`, 오퍼레이션 인자 `responseTimeout`)를 걸고, 메시지를 받을 때마다 다시 건다. 시간이 지나면 `TimeoutException`을 원인으로 `Failed`가 된다(`ReceivingVideo`는 URL이 이미 있으므로 `Completed`). 전체 상한(`sim.get(timeout)`)은 #6과 함께 처리한다. 테스트 `no_response_fails_after_response_timeout`.

### #5 모르는 JSON 필드 하나로 `Failed` — 중간

- 위치: [RCKSimulation.java:274-277](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L274-L277), [VideoInfoMessages.java](../../src/main/java/mdt/ext/rck/VideoInfoMessages.java)
- 문제: `JsonMapper.builder()` 기본값은 `FAIL_ON_UNKNOWN_PROPERTIES = true`다. 시뮬레이터가 `video_info`, `video_progress`, `video_ready`에 필드 하나만 추가해도 `treeToValue`가 예외를 던지고, 상태가 `Failed`로 전이한다.
- 제안: 레코드에 `@JsonIgnoreProperties(ignoreUnknown = true)`를 붙이거나 매퍼에서 이 기능을 끈다. 같은 자리에서 `addModule(new JavaTimeModule())`와 `findAndAddModules()`의 중복도 정리한다.
- 결과: 완료(미커밋). 매퍼에서 `FAIL_ON_UNKNOWN_PROPERTIES`를 끄고, 중복된 모듈 등록(`JavaTimeModule` + `findAndAddModules`)은 없앴다(시간 타입 필드 없음). 테스트의 `video_info`에 모르는 필드 `extra`를 넣었다.

### #6 인터럽트되어도 시뮬레이션은 계속 — 중간

- 위치: [RCKSimulationOperation.java:90-92](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L90-L92)
- 문제: `sim.get()`에서 `InterruptedException`(또는 #4의 타임아웃)이 나면 예외가 그대로 전파되고 차트는 시뮬레이터와 계속 통신한다. 연결과 시뮬레이터 측 작업이 남는다.
- 제안: `try { sim.get(); } catch ( InterruptedException | TimeoutException e ) { sim.cancel(true); throw e; }`처럼 대기가 끝나지 않고 빠져나가는 모든 경로에서 취소한다. `cancel(true)`는 연결되어 있으면 stop을 먼저 보낸다.
- 결과: 2차 작업(미커밋). `sim.get()`이 `InterruptedException`이나 `TimeoutException`(아래 `maxDuration`)으로 끝나면 `sim.cancel(true)`(중지 명령 먼저)로 취소하고 예외를 다시 던진다. 전체 상한은 선택 인자 `maxDuration`. 테스트 `max_duration_cancels_the_simulation_and_sends_stop`.

### #7 `Running`이 받은 `video_info`를 버림 — 중간 (프로토콜 확인 필요)

- 위치: [RCKSimulation.java:505-511](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L505-L511)
- 문제: `Running`은 `video_info`를 파싱만 하고 문맥에 저장하지 않은 채 `WaitingVideo`로 간다. `WaitingVideo`는 **다시** `video_info`를 받아야 `ReceivingVideo`로 간다. 시뮬레이터가 `video_info`를 한 번만 보내면 `WaitingVideo`에서 멈춘다(#4와 겹치면 영원히). 로그 문구도 `received simulation status`로 잘못되어 있다.
- 제안: 실제 메시지 순서를 확인한다(`status` 필드로 두 번 오는지). 한 번이면 `Running`에서 저장하고 바로 다음 단계로 가고, 두 번이면 Javadoc에 그 순서를 적는다.
- 결과: 완료(미커밋). 답변(한 번 보냄)에 따라 `Running`이 `video_info`를 저장하고 바로 `ReceivingVideo`로 간다(크기 0이면 `Completed`). `WaitingVideo` 상태는 없앴고, 그 상태가 처리하던 `video_progress`/`video_ready`는 `Running`이 받는다.

### #8 시뮬레이션 중 서버가 정상 close하면 `CANCELLED` — 중간

- 위치: `Connecting` ~ `Running`, `WaitingVideo` (`ConnectionClosed` 미처리 → `WebSocketStateChart.onUnhandledSignal`)
- 문제: utils의 대체 종료 규칙에 따라 정상 close 코드(1000/1001/1005)는 `cancel`이 된다. 시뮬레이션 도중 서버가 연결을 닫으면 오퍼레이션은 `CancellationException`을 받는다. 사용자가 취소한 것이 아니므로 실패(`Failed`, 원인 "simulator closed the connection")가 맞다.
- 제안: `AbstractTextHandlingState`에서 `ConnectionClosed`를 `Failed`로 전이한다. `ReceivingVideo`·`WaitingVideo`의 현재 동작(`Completed`)은 유지한다.
- 결과: 2차 작업(미커밋). `JsonMessageState`가 `ConnectionClosed`를 받으면 `WebSocketClosedException(code, reason)`을 원인으로 `Failed`가 된다. `ReceivingVideo`/`Stopping`의 기존 동작은 유지. 테스트 `connection_closed_by_simulator_while_running_fails`.

### #9 영상 파일 저장 위치 — 중간

- 위치: [RCKSimulation.java:646](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L646)
- 문제: 파일을 인스턴스 프로세스의 작업 디렉터리에 서버가 준 파일 이름으로 만든다. 같은 이름의 영상을 받는 동시 실행은 서로 덮어쓰고, 완료된 파일은 지워지지 않아 쌓인다.
- 제안: #2에서 파일이 필요 없다고 정하면 저장을 없앤다. 필요하면 임시 디렉터리(`Files.createTempFile`)에 저장하고, 첨부한 뒤 지운다.
- 결과: 완료(미커밋). 파일을 저장하지 않으므로 해당 없음(#2).

### #10 `WaitingVideo.exit()`의 무조건 URL 갱신 — 낮음

- 위치: [RCKSimulation.java:549-562](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L549-L562)
- 문제: STOP·실패·연결 종료로 나갈 때도 `getSimulationVideoInfo().downloadUrl()`를 호출한다. 영상 정보가 없으면 NPE가 나고 ERROR 로그가 남는다.
- 제안: 영상 정보가 있을 때만 갱신한다. 갱신 위치는 `video_info`를 받은 전이 동작으로 옮기는 편이 자연스럽다.
- 결과: 완료(미커밋). `WaitingVideo`를 없앴다(#7). URL 반영은 `video_info` 처리기에서 한다.

### #11 인스턴스 ID를 환경변수에서만 얻음 — 낮음

- 위치: [RCKSimulation.java:893](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L893)
- 문제: `MDTInstanceMain`은 `--id`, `MDT_INSTANCE_ID`, 설정의 `id` 순으로 ID를 정한다. jar 모드는 환경변수를 넣어 주지만, `--id`나 설정 파일로 띄운 인스턴스에서는 MDT 반영이 경고 한 줄과 함께 꺼진다. 또한 인스턴스가 매니저를 거쳐 HTTP로 **자기 자신의** 출력 인자를 갱신하는 구조다.
- 제안: #22에서 오퍼레이션이 ID(또는 출력 갱신 객체)를 넘겨주게 한다.
- 결과: 2차 작업(미커밋). `ProgramCompanion`과 같은 방식으로 `MDTService.getInstanceConfig().getId()`를 쓰고, 서비스 문맥이 없을 때만 `MDT_INSTANCE_ID`를 쓴다.

### #12 `Stopping` 타이머 미취소 — 낮음

- 위치: [RCKSimulation.java:753-754](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L753-L754)
- 문제: `simulation_stop`을 받아 끝난 뒤에도 타이머가 살아 있다가, 끝난 차트에 `STOP_RESPONSE_TIMEOUT_EXPIRED`를 보낸다. 신호는 무시되지만 종료 후 로그가 남고, `delayedExecutor`는 공용 풀을 쓴다.
- 제안: `exit()`에서 취소할 수 있는 스케줄(`ScheduledFuture`)을 쓴다. #4의 상태별 타이머와 같은 도우미로 만든다.
- 결과: 완료(미커밋). `Stopping`도 `WaitingState`의 타이머(`ScheduledFuture`, 세대 번호로 늦은 신호 무시)를 쓰며 `exit()`에서 취소된다.

### #13 이용률 소수점 절삭 — 낮음 (데이터 확인 필요)

- 위치: [RCKSimulationResult.java:88](../../src/main/java/mdt/ext/rck/RCKSimulationResult.java#L88)
- 문제: `equip.get("Utilization").asInt()`는 `45.7`을 `45`로 만든다. 평균은 소수 셋째 자리까지 반올림하는데, 입력은 이미 정수로 잘린 값이다.
- 제안: 시뮬레이터가 실수를 보내면 `asDouble()`로 읽는다. 같은 메소드의 `Conveyor_6`과 `EQUIP_TYPES` 하드코딩은 레이아웃이 바뀌면 깨지므로 설정으로 뺄지 함께 정한다.
- 결과: 2차 작업(미커밋). `asDouble()`로 읽어 평균한다(정수 값이면 결과가 같으므로 데이터 형식과 무관하게 안전). `Conveyor_6`·`EQUIP_TYPES` 하드코딩은 그대로 두었다(레이아웃별 설정이 필요해지면 다시 본다).

### #14 `LayoutName` 오류 처리 — 낮음

- 위치: [RCKSimulationOperation.java:70-74](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L70-L74), [RCKSimulationOperation.java:41-57](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L41-L57)
- 문제: 호출 시점의 입력 변수 오류에 `ConfigurationInitializationException`(설정 초기화 예외)을 던지고, 메시지도 "arguments of JavaOperationProviderConfig"라고 잘못 안내한다. 생성자의 세 인자 검사는 같은 코드가 세 번 반복된다.
- 제안: 입력 오류는 `IllegalArgumentException("input variable 'LayoutName' is empty")`로 바꾼다. 생성자는 `assertStringArgument` + 빈 문자열 검사 도우미로 줄인다.
- 결과: 2차 작업(미커밋). 빈 `LayoutName`은 `IllegalArgumentException("input variable 'LayoutName' is empty")`. 생성자는 `getRequiredNonEmptyArgument`/`getDurationArgument` 도우미로 줄였고, `opRef`가 `null`이어도 로그에서 NPE가 나지 않는다.

## 문서·정리

### #15 README가 코드와 다름 — 낮음

- 위치: [README.md](../../src/main/java/mdt/ext/rck/README.md)
- 내용:
  - 존재하지 않는 `RCKSimulationState` 마커 인터페이스가 구성 요소 표에 있다.
  - `VideoInfo`가 독립 파일처럼 적혀 있지만 실제로는 `VideoInfoMessages` 안의 record다.
  - 입력 인자가 "RCKServerEndpoint, ProcessName, LayoutName"으로 적혀 있지만, 실제로는 설정 인자 `clientId`/`processName`/`rckServerUrl`과 입력 변수 `LayoutName`이다.
  - 출력 인자가 `SimulationVideo`/`State`로 적혀 있지만, 실제로는 `SimulationVideoUrl`과 `FinalState`(오퍼레이션 반환), `State`(중간 반영)다.
- 결과: 완료(미커밋). README를 새 상태 이름·구성 요소·오퍼레이션 인자 표·MDT 연동 방식에 맞게 다시 썼다.

### #16 Javadoc 오류 — 낮음

- 위치: [RCKSimulation.java:522-537](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L522-L537), [RCKSimulation.java:621](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L621)
- 내용: `WaitingVideo`에 `ReceivingVideo`의 Javadoc이 그대로 복사되어 있다. `{@link RCKSimulationContext#getSimulationVideo()}`는 없는 메소드다(`getSimulationVideoInfo()`). 클래스 Javadoc의 상태 목록에도 `WaitingVideo`와 `Stopping`이 빠져 있다.
- 결과: 완료(미커밋). 상태 Javadoc을 모두 새로 썼다(`WaitingVideo` 제거, `Stopping`/`ReceivingVideo` 반영).

### #17 MDT `State` 표시 이름 오타 — 낮음

- 위치: [RCKSimulation.java:102-103](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L102-L103)
- 내용: "소켓 개발"은 "소켓 연결중"으로, "시뮬에이터 연결중"은 "시뮬레이터 등록중"으로 고친다. MDT 화면에 그대로 표시되는 값이다.
- 결과: 완료(미커밋). "소켓 연결중", "시뮬레이터 등록중"으로 고쳤다. `/Stopped`는 "시뮬레이션 중지됨".

### #18 `FinalState`와 `State` 값 형식 불일치 — 낮음

- 위치: [RCKSimulationOperation.java:97](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L97), [RCKSimulation.java:869-879](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L869-L879)
- 내용: 중간 반영(`State`)은 한글 이름("시뮬레이션 완료")을, 오퍼레이션 결과(`FinalState`)는 경로("/Completed")를 쓴다. 소비자가 두 형식을 모두 알아야 한다. 한쪽으로 통일하거나, 기계가 읽는 값(경로/코드)과 표시용 값을 분리한다.
- 결과: 1번(역할 분리) 채택, 미커밋. `FinalState`는 프로그램이 읽는 결과 코드로, 최종 상태 경로에서 `/`를 뗀 값(`Completed`/`Stopped`, `RCKSimulationOperation.toResultCode`)이다. `State`는 사람이 보는 실시간 표시 이름으로 그대로 둔다. 두 출력의 용도와 "실패·취소는 예외로 알리므로 `FinalState`가 기록되지 않고 `State`에만 남는다"는 점을 오퍼레이션 Javadoc, `MDTSimulationOutputs` Javadoc, README(`FinalState`와 `State` 절)에 적었다. 검토 중 확인한 사실: 오퍼레이션이 정상 반환하는 경로는 사실상 `Completed`뿐이다(`requestStop()`을 부르지 않음).

### #19 로깅 불일치 — 낮음

- 위치: [RCKSimulation.java:587](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L587) 외
- 내용: `WaitingVideo`의 예상 밖 메시지만 `System.out.println`으로 출력한다. 나머지는 `s_logger`와 `getLogger()`가 섞여 있다. `getLogger()`로 통일한다(차트 이름 접두어를 쓰려면 `setName`도 함께).
- 결과: 완료(미커밋). `RCKSimulation`은 `getLogger()`만 쓴다(`System.out` 제거).

### #20 운영 코드 안의 개발용 `main()` — 낮음

- 위치: [RCKSimulation.java:1015-1044](../../src/main/java/mdt/ext/rck/RCKSimulation.java#L1015-L1044), [LeafObjectNodeCollector.java:49-75](../../src/main/java/mdt/ext/rck/LeafObjectNodeCollector.java#L49-L75)
- 내용: `LeafObjectNodeCollector.main()`은 `/home/kwlee/tmp/...` 경로와 이미 낡은 집계 로직(필터 없음, 다른 설비 목록)을 담고 있다. 둘 다 `src/test`의 수동 실행 클래스로 옮기거나 삭제한다.
- 결과: 2차 작업(미커밋). `RCKSimulation.main()`은 `src/test/java/mdt/ext/rck/RCKSimulationManualRun`으로 옮겼다. `LeafObjectNodeCollector`는 `main()`과 함께 삭제하고 수집 로직은 `RCKSimulationStatus`의 private 메소드로 옮겼다(#25).

### #21 `RCKSimulationContext.equals/hashCode` — 낮음

- 위치: [RCKSimulationContext.java:160-184](../../src/main/java/mdt/ext/rck/RCKSimulationContext.java#L160-L184)
- 내용: 가변 결과를 담는 문맥 객체에 값 동등성을 정의했다(그것도 `clientId`는 빼고). 상태 차트는 문맥을 동일성(`!=`)으로 비교하므로 필요하지 않다. 사용처가 없으면 제거한다.
- 결과: 2차 작업(미커밋). 사용처가 없어 `equals`/`hashCode`를 제거했다.

## 인터페이스 제안

### #22 MDT 반영을 `RCKSimulation`에서 분리

현재 `RCKSimulation`의 **생성자**가 매니저에 접속해(네트워크 I/O) 출력 인자를 초기화한다. 차트가 시작되기 전에도 출력이 지워지고, 테스트를 위해 `connectMdt` boolean 생성자가 생겼다. MDT 반영 코드는 `updateSimulationState`, `Running`, `WaitingVideo.exit`, `ReceivingVideo.exit`, `connectMdtInstanceManager`에 흩어져 있다.

제안:

```java
/** 시뮬레이션 진행을 외부(MDT 등)에 알리는 통로. 구현의 예외는 시뮬레이션을 멈추지 않는다. */
public interface SimulationObserver {
	default void onStateChanged(String statePath, String displayName) { }
	default void onStatus(RCKSimulationStatus status) { }
	default void onVideoReady(String downloadUrl) { }
	SimulationObserver NONE = new SimulationObserver() { };
}

public RCKSimulation(RCKSimulationContext context, SimulationObserver observer)
```

- MDT 구현(`MDTSimulationOutputs implements SimulationObserver`)은 지금의 `SimulationOutputUpdater`에 `State`와 `SimulationVideoUrl`을 합친 것이다. 인자마다 실패를 따로 다루므로 #3의 "하나 실패 → 전체 꺼짐"이 없어진다.
- `RCKSimulationOperation`이 인스턴스 ID를 알고 구현을 만들어 넘긴다(#11). 테스트는 `SimulationObserver.NONE`을 넘기므로 `connectMdt` 생성자가 필요 없다.
- 결과: 완료(미커밋). `SimulationObserver`(`onStateChanged`/`onStatus`/`onVideoReady`, `NONE`)를 추가하고 `RCKSimulation(context, observer)`로 주입한다. 생성자의 MDT 접속과 `connectMdt` 생성자를 없앴다. MDT 구현은 `MDTSimulationOutputs`(기존 `SimulationOutputUpdater`에 `State`·`SimulationVideoUrl` 추가, `reset()`)이고, 오퍼레이션이 `connectMdtOutputs()`로 만든다. 인스턴스 ID는 아직 환경변수에서 읽는다(#11).

### #23 메시지 `type` 디스패치를 공통 기반으로

`Connecting`/`LayoutLoading`/`SimulationStarting`/`Running`은 `if/else`로 `type`을 비교하고 같은 "Unexpected message type" 분기를 반복한다. `WaitingVideo`와 `Stopping`은 기반 클래스를 쓰지 않고 파싱을 다시 구현한다. `payload.get("type")`은 필드가 없으면 NPE가 난다(결과적으로 `Failed`이지만 원인이 NPE로 남는다).

제안: utils의 `AbstractState.on(...)` 테이블처럼 `type`별 처리기를 등록하는 기반 클래스를 둔다.

```java
private abstract class JsonMessageState extends AbstractState<RCKSimulationContext> {
	private final Map<String, CheckedFunction<JsonNode, Transition<RCKSimulationContext>>> m_handlers = new HashMap<>();

	protected JsonMessageState onMessage(String type, CheckedFunction<JsonNode, Transition<RCKSimulationContext>> handler) { … }
	protected JsonMessageState onMessage(String type, Transition<RCKSimulationContext> transition) { … }
	// TextMessage → path("type") → 처리기, 없으면 경고 후 stay. STOP / SendFailed / ConnectionClosed 공통 처리.
}

// 사용
new JsonMessageState(STATE_LAYOUT_LOADING, ctx)
		.onMessage("loading", Transitions.stay())
		.onMessage("facility", msg -> { ctx.setFacility(msg.get("message")); return Transitions.to(STATE_SIMULATION_STARTING); });
```

- 결과: 완료(미커밋). `JsonMessageState`가 `onMessage(type, transition|handler)`로 처리기를 등록받고, `path("type")`으로 읽는다. STOP·`SendFailed`는 utils `AbstractState.on(...)` 전이 표로 등록한다. `Stopping`·`ReceivingVideo`도 같은 전이 표를 쓴다.

### #24 실패 원인 일원화

실패 원인이 `RCKSimulationContext.failureCause`와 `FailedState`의 원인, 두 곳에 있다. 그래서 `Failed.getFailureCause()`가 둘을 합친다. utils에 `Transitions.fail(path, signal -> cause)`가 생겼으므로 전이에서 원인을 넘기고, 문맥의 `failureCause` 필드와 접근자를 없앤다.

- 결과: 2차 작업(미커밋). `failWith(cause)`가 `Transitions.fail(STATE_FAILED, signal -> cause)`를 돌려준다. 문맥의 `failureCause` 필드·접근자와 `Failed.getFailureCause()` 재정의를 없앴다. 원인은 `waitForFinished().getFailureCause()`로 얻는다.

### #25 `RCKSimulationResult`를 record로

```java
public record RCKSimulationStatus(int progress, int production, Map<String,Float> averageUtilizations) {
	public RCKSimulationStatus { averageUtilizations = Map.copyOf(averageUtilizations); }
	public static RCKSimulationStatus parse(JsonNode statusMessage) { … }
	public static final RCKSimulationStatus EMPTY = …;
}
```

- 불변 맵으로 감싼다. 지금은 `FStream.toMap()` 결과를 그대로 노출한다. MDT에 순서 있게 쓰려면 `TreeMap` 등으로 정렬한다.
- `LeafObjectNodeCollector`는 이 클래스의 private static 메소드로 흡수하거나, 패키지 전용 유틸리티로 바꾼다(지금은 `public` + Guava `Lists`).
- 결과: 2차 작업(미커밋). `record RCKSimulationStatus(int progress, int production, Map<String,Float> averageUtilizations)`. 맵은 유형 이름 순 `TreeMap`을 변경 불가로 감싼다. `LeafObjectNodeCollector`를 흡수했다(Guava 의존 제거).

### #26 오퍼레이션 설정 인자

ping 10초와 pong 2초가 코드에 고정되어 있다([RCKSimulationOperation.java:85-88](../../src/main/java/mdt/ext/rck/RCKSimulationOperation.java#L85-L88)). 같은 클래스의 `main()`은 30초/5초를 쓴다. `pingInterval`/`pongTimeout`/`responseTimeout`(#4)/`maxDuration`을 `JavaOperationProviderConfig` 인자로 받고, 없으면 기본값을 쓴다. pong 2초는 원격 서버(`wss://…dscloud.biz`)에서 짧은 편이다.

- 결과: 2차 작업(미커밋). `responseTimeout`(1차)에 더해 `pingInterval`/`pongTimeout`(기본 10초/2초)과 `maxDuration`(기본 무제한)을 추가했다. 양수가 아니거나 형식이 틀리면 설정 단계에서 `ConfigurationInitializationException`.

## 이름 제안 (#27)

외부에 노출되는 값(MDT `FinalState`에 기록되는 상태 경로)이 바뀌는 항목은 **외부 영향** 열에 표시했다.

| 현재 | 제안 | 이유 | 외부 영향 |
|------|------|------|-----------|
| 상태 `/OpenWebSocket` | `/ConnectingWebSocket` | utils가 같은 상태를 `WebSocketConnectingState`로 바꿨다 | — |
| 상태 `/Connecting` | `/Registering` | 실제로는 `register`를 보내고 `connected`를 기다린다. 위 상태와 혼동된다 | — |
| 상태 `/Cancelled` | `/Stopped` | 차트 결과가 `COMPLETED`다. utils의 `CancelledState → StoppedState`와 같은 이유 | `FinalState` 값 |
| `RCKSimulationResult` | `RCKSimulationStatus` | `simulation_status` 한 건의 스냅숏이다(최종 결과가 아님) | — |
| `getUtilizations()` | `averageUtilizations()` | 설비 유형별 **평균**이다 | — |
| `SimulationOutputUpdater` | `MDTSimulationOutputs` (#22) | 출력 인자 묶음 전체를 다룬다 | — |
| 필드 `m_utilizationUpdater` | `m_outputs` | 이용률만 갱신하지 않는다 | — |
| `VideoInfoMessages` | `VideoMessages` | 영상 관련 메시지 3종의 묶음이다 | — |
| `get/setSimulationVideoInfo` | `get/setVideoInfo` | 문맥 자체가 시뮬레이션 문맥이다 | — |
| `get/setSimulationResult` | `get/setLatestStatus` | 마지막으로 받은 상태 보고다 | — |
| `get/setEquipmentProperties` | `get/setFacility` | 프로토콜 메시지 이름(`facility`)과 맞춘다 | — |
| `STOP` | `STOP_REQUESTED` | 신호가 나타내는 사건 | — |
| `stopSimulation()` | `sendStopCommand()` | 보내기만 하고 멈춤을 기다리지 않는다 | — |
| `sendStopQuietly()` | `trySendStopCommand()` | 실패를 삼키는 시도 | — |
| `watchSend()` | `failOnSendError()` | 하는 일(실패 시 `SendFailed`)을 드러낸다 | — |
| `connectMdtInstanceManager()` | (제거, #22) / `connectMdtOutputs()` | 매니저 접속이 아니라 출력 인자 준비다 | — |
| `PSEUDO_CHUNK_SIZE` / `m_pseudoChunkRemains` | `PROGRESS_LOG_STEP` / `ReceivingVideo` 안의 `m_lastLoggedStep` | 진행 로그 간격일 뿐이다. 이 상태만 쓰는데 지금은 차트 필드다 | — |
| `AbstractTextHandlingState` | `JsonMessageState` (#23) | JSON 메시지를 다룬다 | — |

- 결과: 완료(미커밋). 표의 이름을 모두 적용했다. 다만 getter 형식은 유지해 `getUtilizations()` → `getAverageUtilizations()`로 바꿨다(record 전환은 #25). `/Cancelled` → `/Stopped`는 RCK 밖에서 쓰는 곳이 없음을 확인했다.

## 테스트 (#28)

현재 테스트는 중지·취소 경로만 다룬다. 다음이 없다.

- `RCKSimulationResult.parse` 단위 테스트: 필드 누락, 실수 이용률, 설비 유형 매칭.
- 영상 경로: `video_info` → 이진 수신 완료 / 중간 끊김(#1·#2·#7 회귀).
- `RCKSimulationOperation.invokeSync`: 영상이 없을 때 출력 기록(#1), 인터럽트 시 취소(#6).
- 응답 없는 시뮬레이터에 대한 대기 시간 만료(#4).

- 결과: 1차: 영상 흐름·연결 종료·응답 대기 시간 초과 테스트 추가. 2차 작업(미커밋). `RCKSimulationStatusTest`(4개), `RCKSimulationOperationTest`(5개: 출력 기록, `maxDuration` 취소, 빈 `LayoutName`, 잘못된/올바른 인자), `connection_closed_by_simulator_while_running_fails` 추가. 가짜 서버는 `FakeRckServer`로 분리. RCK 테스트 총 20개 통과.

## 확인이 필요한 질문

- 시뮬레이터가 `video_info`를 몇 번, 어떤 `status`로 보내는가? (#7) → **한 번. 받으면 `ReceivingVideo`로 간다.**
- MDT에 영상 **파일**이 필요한가, URL만 있으면 되는가? (#2, #9) → **일단 URL만.**
- 모델의 `PressSimulation` 출력 `AverageUtilization`은 STRING Property인가, SMC인가? (#3) → **STRING Property.**
- `Utilization` 값은 정수인가, 실수인가? (#13)
- 같은 오퍼레이션을 동시에 여러 번 호출하면 모두 같은 `clientId`로 등록된다. 시뮬레이터가 이를 구분하는가? → **원래는 호출마다 별도 ID여야 한다. 지금은 테스트 편의상 같은 값을 쓴다.**
