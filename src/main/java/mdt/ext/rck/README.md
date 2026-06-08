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
        ├─ 입력 인자(RCKServerEndpoint, ProcessName, LayoutName) 읽기
        ├─ RCKSimulationContext 생성
        ├─ RCKSimulation(상태 차트) 생성·구동 → 종료까지 대기
        └─ 결과(Progress / Production / AverageUtilization / State / SimulationVideo)를
           MDT 오퍼레이션 출력 인자에 기록
```

`RCKSimulation`은 구동되는 동안에도 (MDT 인스턴스에 접속 가능하면) `SimulationOutputUpdater`를 통해
중간 결과를 실시간으로 인스턴스의 출력 인자에 반영한다.

## 상태 차트 흐름 (`RCKSimulation`)

WebSocket 연결 위에서 다음 상태들을 순차적으로 거친다. 각 상태는 시뮬레이터가 보내는 JSON 텍스트 메시지의
`type` 필드로 다음 전이를 결정한다.

```
OpenWebSocket                 # WebSocket 연결 수립
   → Connecting               # register 전송, "connected" 대기
   → LayoutLoading            # layout 요청, "facility"(설비 정보) 수신
   → SimulationStarting       # command(시작) 전송, "simulation_start" 대기
   → Running                  # "simulation_status" 수신(진행), "video_info" 수신 시 →
   → ReceivingVideo           # 이진 메시지로 결과 영상 수신·파일 저장
   → Completed                # 정상 종료

   (사용자 cancel 요청 시)
   ... → Stopping             # stop 전송, "simulation_stop" 대기 → Cancelled
   (오류 발생 시)
   ... → Failed
```

- 종료 상태 `Completed` / `Failed` / `Cancelled` 진입 시 WebSocket을 정상 종료하고 MDT `State` 인자를
  최종 상태로 반영한다.
- 영상 수신 중 연결이 끊기면(`ConnectionClosed`) 시뮬레이션 자체는 종료된 것으로 보아 `Completed`로 전이한다.

자세한 메시지 프로토콜과 전이 조건은 `RCKSimulation`의 Javadoc 및 각 상태 내부 클래스를 참고한다.

## 구성 요소

| 파일 | 역할 |
|------|------|
| `RCKSimulationOperation` | MDT `OperationProvider` 진입점. 입력 인자를 읽어 시뮬레이션을 구동하고 결과를 출력 인자에 기록한다. |
| `RCKSimulation` | `WebSocketStateChart` 기반 시뮬레이션 상태 차트. 시뮬레이터와의 전체 프로토콜과 상태 전이를 정의한다. |
| `RCKSimulationContext` | 상태 차트의 도메인 컨텍스트. 식별 정보(클라이언트 ID·공정명·레이아웃명, 불변)와 진행 중 채워지는 결과(설비 속성·결과·영상·실패 원인, 가변)를 보유한다. |
| `RCKSimulationResult` | 시뮬레이션 한 시점의 결과(진척도·생산량·설비 유형별 평균 이용률). `parse(JsonNode)`로 `simulation_status` 메시지에서 생성한다. |
| `VideoInfo` | 결과 영상 메타데이터(파일명·크기·타임스탬프). `video_info` 메시지에서 역직렬화된다. |
| `SimulationOutputUpdater` | 진행 중 결과(`Progress`/`Production`/`AverageUtilization`)를 MDT 인스턴스 출력 인자에 반영한다. |
| `LeafObjectNodeCollector` | 시뮬레이터 JSON에서 설비별 리프(leaf) 객체 노드를 수집하는 보조 클래스. 이용률 집계에 사용된다. |
| `RCKSimulationState` | RCK 상태들을 위한 마커 인터페이스(`State<RCKSimulationContext>`). |

## MDT 연동

`RCKSimulation`과 `SimulationOutputUpdater`는 다음 표현식 형식으로 인스턴스의 `PressSimulation`
오퍼레이션 출력 인자 reference를 활성화한다.

```
oparg:{instanceId}:PressSimulation:out:{argName}
```

반영되는 출력 인자: `State`, `Progress`, `Production`, `AverageUtilization`, `SimulationVideo`.

- 대상 인스턴스는 `MDT_INSTANCE_ID` 환경변수로 식별한다.
- MDT 인스턴스 매니저 접속에 실패하면 예외를 던지지 않고, MDT 반영 없이 시뮬레이션만 수행한다.

## 의존 라이브러리

- `utils.statechart` / `utils.websocket` — 상태 차트 및 WebSocket 연동 기반.
- `utils.stream` — 설비 이용률 집계(`FStream` / `KeyValueFStream`).
- Jackson — 시뮬레이터 JSON 메시지 직렬화/역직렬화.
- `mdt.model.sm.ref` / `mdt.assetconnection.operation` — MDT 출력 인자 reference 및 오퍼레이션 연동.
