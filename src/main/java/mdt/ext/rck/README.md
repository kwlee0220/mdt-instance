# mdt.ext.rck

RCK 프레스 공정 시뮬레이터와 WebSocket으로 연동하여 시뮬레이션을 수행하고, 그 결과를 MDT 인스턴스의
`PressSimulation` 오퍼레이션 출력 인자에 반영하는 패키지.

MDT 오퍼레이션(`RCKSimulationOperation`)으로 호출되면, 외부 RCK 시뮬레이터 서버에 접속하여 한 건의
시뮬레이션을 끝까지 구동하고 진척도·생산량·설비 이용률·결과 영상을 수집한다. 시뮬레이션 진행은
`utils.statechart` / `utils.websocket` 기반의 상태 차트(`RCKSimulation`)로 모델링되어 있다.

## 동작 개요

```
MDT Operation 호출
   └─ RCKSimulationOperation.invokeSync(...)
        ├─ 설정 인자(clientId, processName, rckServerUrl, 대기 시간들)와 입력 변수(LayoutName) 읽기
        ├─ RCKSimulationContext 생성, MDTSimulationOutputs(관찰자) 준비
        ├─ RCKSimulation(상태 차트) 생성·구동 (maxDuration을 넘으면 취소)
        │    · 비동기 호출(invokeAsync): 시작만 하고 바로 반환, 끝나면 성공/실패 콜백
        │    · 동기 호출(invokeSync): 끝날 때까지 대기, 인터럽트되면 취소
        └─ 결과(Progress / Production / AverageUtilization / FinalState / SimulationVideoUrl)를
           오퍼레이션 출력 변수에 기록
```

`RCKSimulation`은 구동되는 동안 진행 상황을 `SimulationObserver`에 알리고, 어떤 경로로 끝나든(종료 상태, 연결 오류로
인한 대체 종료, 취소) `onFinished`를 한 번 부른다. 오퍼레이션은 MDT 인스턴스에 접속할 수 있으면 `MDTSimulationOutputs`를
넘겨, 중간 결과를 실시간으로 인스턴스의 출력 인자에 반영한다.

## 상태 차트 흐름 (`RCKSimulation`)

WebSocket 연결 위에서 다음 상태들을 순차적으로 거친다. 각 상태는 시뮬레이터가 보내는 JSON 텍스트 메시지의
`type` 필드로 다음 전이를 결정한다.

```
ConnectingWebSocket           # WebSocket 연결 수립 (utils States.WebSocketConnectingState)
   → Registering              # register 전송, "connected" 대기
   → LayoutLoading            # layout 요청, "facility"(설비 정보) 수신
   → SimulationStarting       # command(시작) 전송, "simulation_start" 대기
   → Running                  # "simulation_status" 수신(진행), "video_info" 한 번 수신 시 →
   → ReceivingVideo           # 이진 메시지로 오는 영상을 끝까지 받음(저장하지 않음)
   → Completed                # 정상 종료

   (requestStop() 호출 시, 접속 이후 상태에서)
   ... → Stopping             # stop 전송, "simulation_stop" 응답(기본 10초) 대기 → Stopped
                              #   stop 전송 실패 → Failed
   (오류 발생 시: 명령 전송 실패, 잘못된 메시지, 응답 대기 시간 초과, 시뮬레이터의 연결 종료, 연결 오류)
   ... → Failed
```

- 결과 영상은 `video_info`의 다운로드 URL만 결과로 쓴다. `video_info`를 받으면 URL을 바로 알리므로, 이후 영상
  수신 중 연결이 끊기거나 연결 오류가 나거나 응답이 없어도 `Completed`로 끝난다.
- 연결 오류(pong 시간 초과, JDK `onError`)도 `Failed` 상태를 거친다. 그래서 연결이 아직 열려 있으면 중지 명령을 보낸다.
  `Stopping` 중의 연결 오류는 `Stopped`로 끝난다.
- 형식이 잘못된 `simulation_status`는 경고만 남기고 무시한다. `video_progress` / `video_ready`는 원본 그대로 로그에만
  남긴다(역직렬화하지 않으므로 형식이 달라도 실패하지 않는다).
- 응답을 기다리는 상태(`Registering` ~ `ReceivingVideo`)는 메시지 없이 응답 대기 시간(기본 2분, 오퍼레이션 인자
  `responseTimeout`)이 지나면 `TimeoutException`을 원인으로 `Failed`가 된다. 메시지를 받을 때마다 대기 시간이 다시
  시작된다(진행 메시지가 계속 오면 오래 걸려도 실패하지 않는다).
- 영상 정보를 받기 전(`Registering` ~ `Running`)에 시뮬레이터가 연결을 닫으면 정상 close 코드라도
  `WebSocketClosedException`을 원인으로 `Failed`가 된다. 사용자가 취소한 것이 아니기 때문이다.
- 시뮬레이터 메시지에 모르는 필드가 있어도 무시한다.
- 실패 원인은 `Failed` 상태로 가는 전이(`Transitions.fail`)가 설정하며, 차트의 실행 결과
  (`waitForFinished().getFailureCause()`, `get()`의 `ExecutionException`)로 얻는다.
- 종료 상태 `Completed` / `Failed` 진입 시, 연결이 열려 있으면 **먼저** 시뮬레이터에 stop을 보내고(전송 완료까지
  최대 2초) 그다음 WebSocket을 닫는다. `Stopped`는 `Stopping`에서 이미 stop을 보냈으므로 연결만 닫는다.
- 명령(register, layout, start) 전송이 실패하면 상태가 `Failed`로 전이한다. 전송 결과를 확인하지 않으면 명령이
  사라져도 응답만 기다리게 되기 때문이다.

### 중지와 취소

| 호출 | 동작 | 차트 결과 / 최종 상태 |
|------|------|------|
| `requestStop()` | 접속 이후면 `Stopping`을 거쳐 시뮬레이터의 확인을 받고 끝남. 시작 전·접속 중이면 취소. 상태 콜백이나 `whenStarted` 리스너 안에서 불러 STOP이 큐에 들어갔다 접속 상태에서 처리되어도 취소됨(`onUnhandledSignal`) | `COMPLETED` / `/Stopped` (시작 전·접속 중이면 `CANCELLED`) |
| `cancel(true)` | 연결되어 있으면 stop을 먼저 보낸 뒤(응답은 기다리지 않음) 즉시 취소. `Future.cancel` 규약을 따름 | `CANCELLED` |

`RCKSimulationOperation`은 `maxDuration`(기본 1시간)이 지나거나 동기 호출의 대기가 인터럽트되면 `cancel(true)`로 취소한다.
개발용 수동 실행 프로그램(`src/test/.../RCKSimulationManualRun`)은 시간 초과 시 `requestStop()`을 부른다.

자세한 메시지 프로토콜과 전이 조건은 `RCKSimulation`의 Javadoc 및 각 상태 내부 클래스를 참고한다.

## 구성 요소

| 파일 | 역할 |
|------|------|
| `RCKSimulationOperation` | MDT `OperationProvider` 진입점. 인자를 읽어 시뮬레이션을 구동하고 결과를 출력 변수에 기록한다. |
| `RCKSimulation` | `WebSocketStateChart` 기반 시뮬레이션 상태 차트. 시뮬레이터와의 전체 프로토콜과 상태 전이를 정의한다. |
| `RCKSimulationContext` | 상태 차트의 도메인 컨텍스트. 식별 정보(클라이언트 ID·공정명·레이아웃명, 불변)와 진행 중 채워지는 결과(설비 정보·최근 진행 상황·영상 정보, 가변)를 보유한다. |
| `RCKSimulationStatus` | `simulation_status` 한 건의 진행 상황(진척도·생산량·설비 유형별 평균 이용률) record. `parse(JsonNode)`로 생성한다. |
| `SimulationObserver` | 진행 상황(상태 변화·진행 상황·영상 URL)과 종료(`onFinished`)를 받는 관찰자 인터페이스. `NONE`은 아무 일도 하지 않는다. |
| `MDTSimulationOutputs` | `SimulationObserver` 구현. 진행 상황을 MDT 인스턴스의 출력 인자에 별도 스레드로 반영한다. |
| `VideoInfo` | 결과 영상 정보(`video_info` 메시지) record. 다운로드 URL과 파일 크기를 쓴다. |

## 오퍼레이션 인자

| 인자 | 종류 | 설명 |
|------|------|------|
| `clientId` / `processName` / `rckServerUrl` | 설정(필수) | 시뮬레이터 등록 ID, 공정명, WebSocket 서버 URL |
| `responseTimeout` | 설정(선택) | 응답 대기 시간(예: `"2m"`, `"90s"`). 기본 2분 |
| `pingInterval` / `pongTimeout` | 설정(선택) | 연결 유지 ping 간격 / pong 대기 시간. 기본 10초 / 2초 |
| `maxDuration` | 설정(선택) | 시뮬레이션 전체 최대 수행 시간. 넘으면 취소하고 `TimeoutException`으로 실패. 기본 1시간 |
| `LayoutName` | 입력 변수 | 시뮬레이션할 레이아웃 이름 |
| `Progress` / `Production` / `AverageUtilization` / `FinalState` / `SimulationVideoUrl` | 출력 변수 | 마지막 진행 상황, 이용률 JSON 문자열, 결과 코드(아래), 영상 다운로드 URL(영상 정보가 없으면 빈 문자열) |

### `FinalState`와 `State`

상태를 담는 출력은 두 가지이며 용도가 다르다.

| 출력 | 용도 | 값 | 기록 시점 |
|------|------|----|-----------|
| `FinalState` (오퍼레이션 출력 변수) | 프로그램이 읽는 결과 코드 | `Completed` / `Stopped` (최종 상태 경로에서 `/`를 뗀 값) | 오퍼레이션이 정상 반환할 때 한 번 |
| `State` (`MDTSimulationOutputs`가 반영하는 출력 인자) | 사람이 보는 실시간 표시 | `시뮬레이션 진행중`, `시뮬레이션 완료`, `시뮬레이션 실패` 등 | 상태가 바뀔 때마다 |

- 실패·취소·시간 초과는 오퍼레이션이 예외로 알리므로 출력 변수가 기록되지 않는다. 그런 경우의 마지막 상태
  (`시뮬레이션 실패` / `시뮬레이션 취소됨`)는 `State`에만 남는다(MDT 인스턴스에 접속했을 때).
- 접속 단계에서 `State`는 `소켓 연결중`이 된다. 이전 실행의 값이 남지 않는다.
- `State`의 표시 문구는 바뀔 수 있으므로 분기 조건으로 쓰지 않는다. 분기에는 `FinalState`나 오퍼레이션의 성공/실패를 쓴다.

## MDT 연동

`MDTSimulationOutputs`는 다음 표현식 형식으로 인스턴스의 `PressSimulation` 오퍼레이션 출력 인자 reference를
활성화한다.

```
oparg:{instanceId}:PressSimulation:out:{argName}
```

반영되는 출력 인자: `State`(한글 상태 이름), `Progress`, `Production`, `AverageUtilization`(JSON 문자열),
`SimulationVideoUrl`.

- 대상 인스턴스는 이 인스턴스의 설정 id(`--id` → `MDT_INSTANCE_ID` → 설정 파일)로 식별하고, 서비스 문맥이 없으면
  `MDT_INSTANCE_ID` 환경변수를 쓴다.
- MDT 인스턴스 매니저 접속에 실패하면 예외를 던지지 않고, MDT 반영 없이 시뮬레이션만 수행한다.
- 인자는 하나씩 따로 준비·갱신한다. 한 인자가 없거나 갱신에 실패해도 경고 로그만 남기고 나머지는 반영한다.
- 갱신은 별도 스레드가 한다. 관찰자 메소드는 갱신을 기록만 하고 바로 반환하므로 MDT가 느려도 시뮬레이션 메시지
  처리가 밀리지 않는다. 갱신이 밀려 있는 동안 같은 인자에 새 값이 오면 최신 값만 쓴다. 따라서 오퍼레이션이 반환된
  직후에는 마지막 `State` 갱신이 아직 반영되지 않았을 수 있다.

## 의존 라이브러리

- `utils.statechart` / `utils.websocket` — 상태 차트 및 WebSocket 연동 기반.
- `utils.stream` — 설비 이용률 집계(`FStream`).
- Jackson — 시뮬레이터 JSON 메시지 직렬화/역직렬화.
- `mdt.model.sm.ref` / `mdt.assetconnection.operation` — MDT 출력 인자 reference 및 오퍼레이션 연동.
