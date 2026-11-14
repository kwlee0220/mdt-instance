# RCK 시뮬레이션 사용 가이드

`mdt.ext.rck`는 RCK 프레스 공정 시뮬레이터에 WebSocket으로 접속하여 레이아웃을 실행하고, 진행 상황·생산량·설비 이용률·결과 영상 URL을 수집한다. MDT 오퍼레이션으로 등록하거나 Java 코드에서 직접 실행할 수 있다.

**목차**

- [1. 사용 방식과 구성 요소](#1-사용-방식과-구성-요소)
- [2. MDT 오퍼레이션으로 사용하기](#2-mdt-오퍼레이션으로-사용하기)
- [3. Java에서 직접 실행하기](#3-java에서-직접-실행하기)
- [4. 진행 상황과 결과 해석하기](#4-진행-상황과-결과-해석하기)
- [5. 중지·취소·시간 제한](#5-중지취소시간-제한)
- [6. 통신 흐름과 문제 해결](#6-통신-흐름과-문제-해결)
- [7. 관련 코드와 검증](#7-관련-코드와-검증)

## 1. 사용 방식과 구성 요소

### 1.1. 어떤 방식으로 사용할 것인가

| 목적 | 진입점 | 준비할 항목 |
|---|---|---|
| MDT 연산으로 실행하고 출력 인자를 조회 | `RCKSimulationOperation` | AAS Operation 모델, Java 연산 설정, `LayoutName` 입력 |
| Java 프로그램에서 직접 실행하고 결과 처리 | `RCKSimulation` | 서버 URL, 클라이언트 ID, 공정명, 레이아웃명 |
| 직접 실행하면서 MDT에 진행 상황 반영 | `RCKSimulation` + `MDTSimulationOutputReporter` | 위 항목과 MDT 관리자·대상 인스턴스 ID |

예제의 서버 주소·공정명·레이아웃명은 사용 환경에 맞게 바꿔야 한다. 레이아웃은 RCK 서버에서 사용할 수 있는 이름을 지정한다. 이 패키지가 로컬 레이아웃 파일을 업로드하지는 않는다.

### 1.2. 구성 요소

| 클래스 | 사용자가 알아둘 역할 |
|---|---|
| [RCKSimulation](RCKSimulation.java) | `builder()`로 생성, 실행 시작, 종료 대기, 중지·취소, 최종 결과 조회 |
| [RCKSimulationContext](RCKSimulationContext.java) | 접속·실행 대상 정보와 실행 중 수신한 정보 보관. 빌더가 만들며 `getContext()`로 읽기만 한다 |
| [SimulationObserver](SimulationObserver.java) | 단계 변화, 진행 상황, 영상 URL, 종료 결과 알림 |
| [RCKSimulationPhase](RCKSimulationPhase.java) | 진행 단계(접속, 등록, 레이아웃 로딩, 시작, 진행, 중지)와 표시 이름 |
| [RCKSimulationProgress](RCKSimulationProgress.java) | 진척도·생산량·설비별 이용률 |
| [RCKSimulationResult](RCKSimulationResult.java) | 종료 사유, 중지 확인 여부, 마지막 진행 상황, 영상 URL, 실패 원인 |
| [RCKSimulationOperation](RCKSimulationOperation.java) | MDT OperationProvider와 시뮬레이션 연결 |
| [LoadEquipmentPropertiesList](LoadEquipmentPropertiesList.java) | 시뮬레이션 없이 설비 속성 목록을 JSON 문자열로 출력하는 MDT OperationProvider |
| [MDTSimulationOutputReporter](MDTSimulationOutputReporter.java) | MDT 출력 인자에 진행 상황을 비동기로 반영 |
| [RCKExecutors](RCKExecutors.java) | 내부 타이머·작업용 데몬 스레드 관리. 직접 생성할 필요 없음 |

```mermaid
flowchart LR
    A[MDT 오퍼레이션 호출] --> B[RCKSimulationOperation]
    J[Java 프로그램] --> C[RCKSimulation]
    B --> C
    C <-->|WebSocket| D[RCK 시뮬레이터]
    C -->|진행 알림| E[SimulationObserver]
    E --> F[로그 또는 사용자 화면]
    E --> G[MDTSimulationOutputReporter]
    G -->|비동기 갱신| H[MDT 출력 인자]
    C -->|종료 결과| B
    C -->|종료 결과| J
```

## 2. MDT 오퍼레이션으로 사용하기

### 2.1. 모델과 연산 설정 준비

대상 AAS Operation과 입출력 변수는 모델에 미리 정의한다. 제공자 등록만으로 모델이나 변수가 생성되지는 않는다. 연산 등록 절차는 [assetconnection 가이드](../../assetconnection/README.md)를 참고한다.

#### 2.1.1. Java 제공자 설정 예제

아래는 `JavaOperationProviderConfig`에 해당하는 설정 조각이다. 인스턴스 연산 설정에서 대상 Operation에 연결하는 `java` 설정으로 사용한다. 전체 인스턴스 설정 파일은 아니다.

```json
{
  "operationClassName": "mdt.ext.rck.RCKSimulationOperation",
  "arguments": {
    "clientId": "mdt-client-01",
    "processName": "press",
    "rckServerUrl": "ws://localhost:4000/rck/simulator",
    "responseTimeout": "2m",
    "pingInterval": "10s",
    "pongTimeout": "2s",
    "maxDuration": "1h"
  }
}
```

| 설정 인자 | 필수 | 기본값 | 의미 |
|---|---|---|---|
| `clientId` | 예 | 없음 | RCK 서버에 등록할 클라이언트 ID |
| `processName` | 예 | 없음 | 실행할 공정명 |
| `rckServerUrl` | 예 | 없음 | RCK WebSocket URL (`ws://` 또는 `wss://`) |
| `responseTimeout` | 아니요 | `2m` | 응답 대기 상태에서 메시지 없이 기다릴 최대 시간 |
| `pingInterval` | 아니요 | `10s` | 연결 유지 ping 간격 |
| `pongTimeout` | 아니요 | `2s` | pong 응답 대기 시간 |
| `maxDuration` | 아니요 | `1h` | 시뮬레이션 전체 실행 제한 시간 |
| `productionPath` | 아니요 | `Utilizations.Conveyor_6.Production` | 진행 보고에서 생산량을 읽을 필드 경로(`.`으로 구분) |

필수 문자열은 공백만으로 지정할 수 없다. 시간 설정은 `90s`, `2m`처럼 `UnitUtils.parseDuration()`이 해석하는 문자열이며 양수여야 한다. 형식이 잘못되면 제공자 초기화가 실패한다. `productionPath`에 빈 항목(`a..b`)이 있어도 실패한다. 레이아웃이 바뀌어 생산량 위치가 달라지면 이 인자로 맞춘다(직접 실행할 때는 빌더의 `progressFormat(...)`).

#### 2.1.2. 입출력 변수 정의

변수 이름은 `OperationVariable` 안에 있는 요소의 `idShort`다. 아래 변수는 모두 AAS `Property`로 정의한다.

| 방향 | 이름 | `valueType` | 의미 |
|---|---|---|---|
| 입력·필수 | `LayoutName` | `xs:string` | 실행할 레이아웃 이름. 빈 값·공백 불가 |
| 입력·선택 | `EquipmentPropertiesList` | `xs:string` | 시뮬레이터가 보낸 설비 속성에 덮어쓸 값. `facility` 메시지의 `message`와 같은 형식의 JSON 문자열(3.6절). 없거나 빈 문자열이면 `{}`(바꿀 설비 없음) |
| 출력·선택 | `Progress` | `xs:int` | 마지막 진척도(%) |
| 출력·선택 | `Production` | `xs:int` | 마지막 생산량 |
| 출력·선택 | `EquipmentUtilizationList` | `xs:string` | 설비별 이용률을 담은 JSON 문자열 |
| 출력·선택 | `SimulationVideoUrl` | `xs:string` | 영상 URL. 영상 정보가 없으면 빈 문자열 |

출력 변수는 생략할 수 있지만, 정의한다면 위 타입에 맞춰야 한다. 입력·출력 형식은 실행 전에 검사한다. 이 오퍼레이션은 입출력 겸용 변수(`inoutputVars`)를 사용하지 않는다.

### 2.2. 호출과 결과 확인

1. 대상 Operation에 위 Java 제공자를 연결하고 인스턴스를 실행한다.
2. 호출 입력 `LayoutName`에 서버에 등록된 레이아웃 이름을 넣는다.
3. 동기 호출은 실행 종료까지 기다린다. 비동기 호출은 실행을 시작한 뒤 반환하며 완료 시 성공·실패 콜백으로 알린다.
4. 성공하면 모델에 정의된 출력 변수에서 결과를 읽는다. 실패·취소·전체 시간 초과는 예외 또는 실패 콜백으로 처리한다.
5. 한 오퍼레이션은 시뮬레이션을 한 번에 하나만 실행한다. 실행 중에 들어온 호출은 시작하지 않고 `IllegalStateException`으로 거부한다. 결과 콜백이 불릴 때는 이미 다음 호출을 받을 수 있다.

입력 예: `LayoutName = "프레스 공정_사람_NOCON.vcmx"`, `EquipmentPropertiesList = "{\"Press\":{\"Name\":\"TP-75\",\"Index\":1,\"Time\":40}}"`.

`EquipmentPropertiesList`가 JSON이 아니거나 형식에 맞지 않으면(예: 배열, `Name`·`Index` 누락, 숫자가 아닌 속성) 시뮬레이터에 접속하지 않고 `IllegalArgumentException`으로 실패한다.

성공 결과의 예는 다음과 같다. 실제 값은 시뮬레이터의 보고 내용에 따라 달라진다.

```text
Progress            = 100
Production          = 120
EquipmentUtilizationList = [{"group":"Press","equipment":"TP-75","index":1,"utilization":97.77},
                       {"group":"Press","equipment":"TP-75","index":2,"utilization":97.77},
                       {"group":"Washer_1","equipment":"세척기","index":1,"utilization":48.57}, ...]
SimulationVideoUrl  = https://simulator.example/results/run-001.mp4
```

`EquipmentUtilizationList`는 AAS 컬렉션이 아니라 **JSON을 담은 문자열 Property**다. 진행 보고의 순서를 따르는 배열이며, 각 원소는 설비 그룹(`group`), 설비 이름(`equipment`), 그룹 안의 설비 번호(`index`), 이용률(`utilization`)을 담은 객체다. 수치 단위는 시뮬레이터가 보낸 값을 그대로 따르므로 화면에서 임의로 100을 곱하지 않는다.

### 2.3. MDT에 실시간 진행 상황 반영

오퍼레이션은 갱신기를 자동으로 생성하고, **이 연산이 등록된 시뮬레이션 Submodel**의 출력 인자에 진행 상황을 쓴다. 대상 Submodel은 연산 reference의 Submodel id로 정하므로, Submodel의 idShort가 무엇이든 따로 설정할 필요가 없다. 참조 표현식으로 쓰면 다음과 같다.

```text
oparg:submodel:id={submodelId}:out:{argName}
```

연산 reference가 없는 경우(시험 등)에만 인스턴스의 `PressSimulation` Submodel(`oparg:{instanceId}:PressSimulation:out:{argName}`)을 쓴다.

실시간 반영 대상은 `State`, `Progress`, `Production`, `EquipmentUtilizationList`, `SimulationVideoUrl`이다. `State`는 문자열 출력 인자로 준비한다.

`State`의 한글 문구는 분기 조건으로 사용하지 않는다. 실패 후 저장된 출력 값은 이전 실행의 값일 수도 있으므로, 호출의 성공 여부를 먼저 확인한다.

위 대체 경로의 인스턴스 ID는 `MDTService`의 인스턴스 설정에서 찾고, 사용할 수 없으면 `MDT_INSTANCE_ID` 환경변수를 확인한다. 관리자 접속은 `HttpMDTManager.connectWithDefault()`를 사용한다. 관리자나 개별 출력 인자에 접근하지 못해도 시뮬레이션은 계속되며, 경고 로그를 남긴다.

갱신은 별도 스레드에서 순서대로 처리한다. 같은 인자의 갱신이 밀리면 최신 값만 남기며, 새 실행이 시작되면 이전 실행의 미처리 갱신은 버린다. 따라서 공용 출력 인자는 실행별 이력 저장소가 아니다. 오퍼레이션은 결과를 통지하기 전에 갱신 완료를 최대 5초 기다리고, 그 시간을 넘으면 경고를 남긴 뒤 결과를 통지한다.

## 3. Java에서 직접 실행하기

### 3.1. 실행하고 최종 결과 읽기

다음 예제는 MDT 연동 없이 RCK 시뮬레이션 한 건을 실행한다. 직접 실행에는 오퍼레이션의 `maxDuration`이 적용되지 않으므로 호출자가 종료 대기 시간과 시간 초과 처리를 정한다.

```java
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mdt.ext.rck.*;

public class RunRck {
    public static void main(String[] args) throws Exception {
        RCKSimulation simulation = RCKSimulation.builder()
                .serverUrl("ws://localhost:4000/rck/simulator")
                .clientId("mdt-client-01")
                .processName("press")
                .layoutName("프레스 공정_사람_NOCON.vcmx")
                .responseTimeout(Duration.ofMinutes(2))                       // 선택, 기본 2분
                .keepAlive(Duration.ofSeconds(10), Duration.ofSeconds(2))      // 선택: ping 주기, pong 대기
                .build();
        simulation.start();

        try {
            simulation.waitForFinished(50, TimeUnit.MINUTES);
        }
        catch (TimeoutException e) {
            // 대기 시간 초과만으로 실행이 멈추지는 않는다.
            simulation.requestStop();
            simulation.waitForFinished();
        }
        catch (InterruptedException e) {
            simulation.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }

        RCKSimulationResult result = simulation.getSimulationResult();
        System.out.println("종료 사유: " + result.termination());
        System.out.println("생산량: " + result.progress().production());
        System.out.println("영상 URL: " + result.videoUrl());
        if (result.failureCause() != null) {
            result.failureCause().printStackTrace();
        }
    }
}
```

`waitForFinished()`가 반환됐다는 사실만으로 정상 완료를 판단하지 않는다. 종료 후 `getSimulationResult().termination()`을 확인한다. 실행 중에는 `getSimulationResult()`가 `IllegalStateException`을 던진다. 실행마다 `build()`로 새 simulation 객체를 만든다. 서버 URL·클라이언트 ID·공정명·레이아웃명이 빠지면 `build()`가 `IllegalArgumentException`을 던진다.

### 3.2. 진행 알림 받기

빌더의 `observer(...)`로 `SimulationObserver`를 넘기면 단계·진행률·영상 URL·종료 결과를 받을 수 있다. 아래는 화면이나 로그 처리에 필요한 콜백을 연결하는 예제다.

```java
SimulationObserver observer = new SimulationObserver() {
    @Override
    public void onPhaseChanged(RCKSimulationPhase phase) {
        System.out.println(phase + " : " + phase.displayName());
    }

    @Override
    public void onProgressUpdated(RCKSimulationProgress progress) {
        System.out.println("진척도=" + progress.progress()
                + ", 생산량=" + progress.production());
    }

    @Override
    public void onVideoReady(String downloadUrl) {
        System.out.println("영상 URL=" + downloadUrl);
    }

    @Override
    public void onFinished(RCKSimulationResult result) {
        System.out.println("종료=" + result.termination());
    }
};

var simulation = RCKSimulation.builder()
        .serverUrl(url).clientId(clientId).processName(processName).layoutName(layoutName)
        .observer(observer)
        .build();
simulation.start();
```

`url` 등 접속 정보는 3.1절과 같다. 단계는 `RCKSimulationPhase`로 오며, 표시 이름(`displayName()`)은 바뀔 수 있으므로 분기에는 enum 값을 쓴다. 종료 상태는 단계가 아니라 `onFinished`로 온다. 콜백은 잠금을 보유한 상태에서 호출될 수 있으므로 원격 요청, 파일 다운로드, 종료 대기처럼 오래 걸리는 작업을 직접 수행하지 않는다. 그런 작업은 호출자가 관리하는 executor나 화면 이벤트 큐로 넘긴다. 관찰자의 예외는 경고로 남기고 무시된다. 알림이 필요 없으면 `observer(...)`를 생략한다(`SimulationObserver.NONE`).

### 3.3. 직접 실행하면서 MDT 출력 반영하기

이미 준비한 `MDTInstanceManager manager`가 있다면 갱신기를 관찰자로 사용할 수 있다. 두 번째 인자는 출력 인자가 있는 시뮬레이션 Submodel의 참조다(Submodel id로는 `DefaultSubmodelReference.ofId(id)`).

```java
var reporter = new MDTSimulationOutputReporter(
        () -> manager, () -> DefaultSubmodelReference.ofIdShort("press-instance", "PressSimulation"));

// 같은 출력 대상의 여러 실행에서는 reporter를 재사용한다.
var simulation = RCKSimulation.builder()
        .serverUrl(url).clientId(clientId).processName(processName).layoutName(layoutName)
        .observer(reporter.newRunObserver())
        .build();
simulation.start();
simulation.waitForFinished();
reporter.flushAsync().get(5, TimeUnit.SECONDS);
RCKSimulationResult result = simulation.getSimulationResult();
```

`newRunObserver()`는 실행마다 새 관찰자를 반환한다. 생성자와 `newRunObserver()` 자체는 MDT 접속을 기다리지 않는다. `flushAsync()`는 대기 중인 갱신 처리가 끝날 때 완료되지만, 개별 갱신 실패는 경고로 처리하므로 모든 출력의 저장 성공을 보증하는 것은 아니다. 위 예제의 5초 대기에서 발생하는 `TimeoutException`은 호출자가 처리한다.

### 3.4. 시뮬레이션 없이 설비 정보만 얻기

레이아웃의 설비 정보만 필요하면 `build()` 대신 `loadEquipmentProperties()`를 호출한다. 호출한 스레드는 끝날 때까지 기다린다.

```java
RCKEquipmentPropertiesList props = RCKSimulation.builder()
        .serverUrl(url).clientId(clientId).processName(processName).layoutName(layoutName)
        .loadEquipmentProperties();

for ( RCKEquipment equipment: props.equipments() ) {
    System.out.println(equipment + ": " + props.propertiesOf(equipment));
}
```

접속, 등록, 레이아웃 요청까지만 수행하고, `facility` 메시지를 받으면 연결을 닫는다. 시작 명령과 중지 명령은 실패나 인터럽트로 끝날 때를 포함해 보내지 않는다. 다만 같은 공정에서 시뮬레이션이 실행 중일 때 레이아웃 요청이 시뮬레이터에 주는 영향은 확인되지 않았다.

설비 정보를 얻지 못하면 `ExecutionException`이 발생하고, 원인에서 접속 실패, 응답 대기 시간 초과, 연결 종료, `facility` 메시지 형식 오류 등을 확인할 수 있다. 기다리는 중 인터럽트되면 실행을 취소한 뒤 `InterruptedException`이 발생한다. `observer(...)`로 지정한 관찰자에게는 알리지 않는다.

### 3.5. MDT 오퍼레이션으로 설비 속성 목록 얻기

`LoadEquipmentPropertiesList`는 3.4절의 호출을 MDT 오퍼레이션으로 제공한다. 시뮬레이션은 실행하지 않는다.

```json
"PressSimulation:Operations.LoadEquipmentPropertiesList": {
  "className": "mdt.ext.rck.LoadEquipmentPropertiesList",
  "arguments": {
    "clientId": "mdt-equipment",
    "rckServerUrl": "ws://localhost:4000/rck/simulator",
    "processName": "press"
  }
}
```

설정 인자 `clientId`, `processName`, `rckServerUrl`은 필수이고, `responseTimeout`은 선택(기본 `2m`)이다.

| 방향 | 이름 | `valueType` | 의미 |
|---|---|---|---|
| 입력·필수 | `LayoutName` | `xs:string` | 설비 속성을 읽을 레이아웃 이름. 빈 값·공백 불가 |
| 출력·필수 | `EquipmentPropertiesList` | `xs:string` | 시뮬레이터가 보낸 `facility` 메시지의 `message`와 같은 형식의 JSON 문자열 |

한 오퍼레이션은 한 번에 하나의 요청만 처리하며, 처리 중에 들어온 호출은 `IllegalStateException`으로 거부한다. 설비 속성을 얻지 못하면 호출이 실패하고 출력 변수는 기록되지 않는다.

### 3.6. 설비 속성 값을 바꿔 실행하기

시뮬레이터가 `facility` 메시지로 보낸 설비 속성 대신 다른 값으로 실행하려면 빌더의 `overrideEquipmentProperties(...)`에 바꿀 값을 준다. 값은 `facility` 메시지의 `message`와 같은 형식으로 만든다.

```java
RCKEquipmentPropertiesList overrides = RCKEquipmentPropertiesList.parse(JacksonUtils.MAPPER.readTree("""
        { "Press": { "Name": "TP-75", "Index": 1, "Time": 40 } }
        """));

RCKSimulation simulation = RCKSimulation.builder()
        .serverUrl(url).clientId(clientId).processName(processName).layoutName(layoutName)
        .overrideEquipmentProperties(overrides)
        .build();
```

`LayoutLoading`에서 설비 정보를 받으면, 그룹·`Name`·`Index`가 모두 같은 설비의 속성 값을 지정한 값으로 바꾼다. 지정하지 않은 설비와 속성은 받은 값을 그대로 쓰며, 바뀐 설비 정보가 시작 명령의 `message`로 나간다. 시뮬레이터가 보낸 목록에 없는 설비나 속성은 추가하지 않고 경고 로그만 남긴다. `loadEquipmentProperties()`(3.4절)도 같은 빌더 설정을 따르므로, 지정하면 바뀐 값을 돌려준다.

## 4. 진행 상황과 결과 해석하기

### 4.1. 진행 상황

`RCKSimulationProgress`의 필드는 다음과 같다.

| 필드 | 의미 |
|---|---|
| `progress()` | `simulation_status.message.Progress`를 정수로 읽은 진척도 |
| `production()` | 생산량 필드(기본 `Utilizations.Conveyor_6.Production`)를 정수로 읽은 값. 필드가 없으면 0 |
| `utilizations()` | 설비별 이용률 `Map<RCKEquipment,Float>`. 진행 보고의 순서를 따르는 변경 불가 맵 |

이용률은 진행 보고의 `Utilizations` 객체를 읽는다. 필드 이름이 설비 그룹이고, 값은 설비 하나를 나타내는 객체(`{"Name": .., "Index": .., "Utilization": ..}`)이거나 그 배열이다. `Name`(문자열), `Index`(`int` 범위의 정수), `Utilization`(숫자)이 모두 맞는 설비만 읽고, 값은 시뮬레이터가 보낸 값을 `float`으로 바꾸어 쓴다. 같은 설비가 두 번 나오면 나중 값을 쓴다. `Utilizations`가 없으면 빈 맵이고, 객체가 아니면 보고 전체를 무시한다. `ProcessName`은 읽지 않는다. 첫 진행 보고 전에는 진척도·생산량이 0이고 이용률 맵은 비어 있다.

`Progress`가 없거나 숫자가 아니면 해당 보고를 무시한다. 생산량 필드도 있는데 숫자가 아니면 보고 전체를 무시한다. 이전에 받은 정상 진행 상황은 유지된다.

### 4.2. 종료 결과

| `termination()` | 의미 | 추가 확인 |
|---|---|---|
| `COMPLETED` | 정상 종료 | `videoUrl()` 확인 |
| `STOPPED` | 중지 절차로 종료 | `stopConfirmed()`가 true일 때만 서버의 중지 응답을 받음 |
| `FAILED` | 통신·프로토콜·응답 대기 실패 등 | `failureCause()` 확인 |
| `CANCELLED` | 취소 | 오퍼레이션의 전체 시간 초과도 내부적으로 취소 처리 |

`STOPPED`는 상태 차트 실행 자체로는 성공이다. 서버 응답 없이 중지 대기 시간이 끝나거나 연결이 종료돼도 `STOPPED`가 될 수 있으므로, 서버의 중지 확인이 필요하면 `stopConfirmed()`를 함께 본다.

### 4.3. 결과 영상

`video_info`를 받으면 URL을 관찰자에게 알리고 바로 `COMPLETED`로 끝난다. 이 패키지는 영상 파일을 받거나 저장하지 않으므로, 영상 파일이 필요하면 `videoUrl()`의 주소에서 별도로 내려받는다.

따라서 정상 완료는 URL의 다운로드 성공까지 보장하지 않는다. `video_info`에 URL이 없어도 `COMPLETED`로 끝나며, 이때와 영상 정보를 받기 전에 끝난 경우 `videoUrl()`은 null이다.

## 5. 중지·취소·시간 제한

### 5.1. 중지와 취소 선택

| 호출 | 동작 | 결과 |
|---|---|---|
| `requestStop()` | 접속 이후 stop 명령을 보내고 확인 응답을 기다림 | 보통 `STOPPED`. 시작 전·접속 중이면 `CANCELLED` |
| `cancel(true)` | 연결돼 있으면 stop 전송을 시도한 뒤 취소. 서버 확인 응답은 기다리지 않음 | `CANCELLED` |

```java
// 사용자 중지 버튼 등에서 호출한다.
simulation.requestStop();

// 종료 절차를 기다릴 수 없을 때 선택한다.
// simulation.cancel(true);
```

stop 전송 실패는 `FAILED`가 될 수 있다. 취소도 stop 전송 완료를 최대 2초 기다릴 수 있으므로 지연에 민감한 화면 스레드에서는 작업 스레드로 넘긴다.

### 5.2. 시간 제한의 차이

| 제한 | 의미 | 설정 위치 |
|---|---|---|
| 응답 대기 | 메시지가 오지 않는 구간 제한. 메시지 수신 시 다시 시작 | 오퍼레이션 `responseTimeout` / 빌더 `responseTimeout()` |
| 중지 응답 대기 | stop 명령의 응답을 기다리는 시간. 기본 10초 | 직접 실행 시 빌더 `stopResponseTimeout()` |
| 전체 실행 시간 | 메시지가 계속 와도 적용되는 전체 제한 | 오퍼레이션 `maxDuration` |
| 호출자 대기 시간 | 호출자가 완료를 기다리는 시간. 초과만으로 자동 취소되지 않음 | `waitForFinished(timeout, unit)` |

오퍼레이션은 `maxDuration`이 지나면 취소하고 시간 초과로 보고한다. 동기 호출의 대기가 인터럽트되면 취소한다. 직접 실행에서는 이러한 전체 제한 처리를 호출자가 구현해야 한다.

## 6. 통신 흐름과 문제 해결

### 6.1. 정상 실행 흐름

```mermaid
stateDiagram-v2
    [*] --> ConnectingWebSocket
    ConnectingWebSocket --> Registering: 연결 성공
    Registering --> LayoutLoading: connected
    LayoutLoading --> SimulationStarting: facility
    SimulationStarting --> Running: simulation_start
    Running --> Running: simulation_status
    Running --> Completed: video_info
    Running --> Stopping: requestStop
    Stopping --> Stopped: 중지 응답 또는 대기 종료
    Completed --> [*]
    Stopped --> [*]
```

그림은 주요 경로만 표시한다. 등록·레이아웃 로딩·시작 대기 상태에서도 중지를 요청할 수 있으며, 오류 경로에서는 `Failed`로 전이한다.

| 단계 | 송신 내용 | 기다리는 응답 |
|---|---|---|
| 등록 | `{"register":"<clientId>"}` | `connected` |
| 레이아웃 선택 | `type=layout`, `target=processName`, `name=layoutName` | `facility` (`loading`은 대기 유지) |
| 실행 시작 | `type=command`, `target=processName`, `message=facility 정보` (`facility`의 `message`와 같은 형식) | `simulation_start` (`simulation_waiting`은 대기 유지) |
| 실행 중 | 진행 보고 수신 | `simulation_status`, `video_info` |
| 중지 | `type=stop`, `target=processName` | `simulation_stop` |

진척도가 100이라는 이유만으로 종료하지 않는다. 정상 실행은 `video_info`를 받으면 끝난다. `video_progress`와 `video_ready`는 로그에만 남긴다.

### 6.2. 자주 확인할 사항

| 증상 | 확인할 내용 |
|---|---|
| 실행 전에 예외 발생 | 필수 설정, 양수 시간 값, `LayoutName`, `EquipmentPropertiesList` 형식, 출력 Property 타입 |
| 등록·레이아웃 단계에서 시간 초과 | URL·클라이언트 등록 방식·공정명·서버 레이아웃 이름과 응답 로그 |
| 진행 중 갑자기 실패 | `failureCause()`, pong 시간 초과, 영상 정보 수신 전 연결 종료 여부 |
| 실행은 되지만 MDT 상태가 바뀌지 않음 | 관리자 접속 설정, 연산이 등록된 Submodel의 출력 인자와 경고 로그 |
| 중지됐지만 서버 확인 여부가 불명확 | `stopConfirmed()` 확인 |
| 완료됐는데 영상 파일이 없음 | 이 패키지는 파일을 저장하지 않음. 결과 URL에서 별도 다운로드 |
| 비동기 호출에서 오류를 찾기 어려움 | 실패 콜백의 예외와 원인 확인. 일부 실패는 `ExecutionException`으로 전달됨 |

상태 처리와 관찰자는 빠르게 반환하도록 작성한다. 내부 타이머·취소·MDT 갱신에는 RCK 전용 스레드를 사용하며, 명령 송신은 앞선 송신이 끝난 뒤 순서대로 보낸다. `Completed`·`Failed` 진입 시 연결이 열려 있으면 stop 전송을 시도한 뒤 연결을 닫는다.

## 7. 관련 코드와 검증

- [RCKSimulation.java](RCKSimulation.java): 프로토콜과 상태별 처리
- [RCKSimulationOperation.java](RCKSimulationOperation.java): 설정·입출력·시간 제한·콜백 처리
- [LoadEquipmentPropertiesList.java](LoadEquipmentPropertiesList.java): 설비 속성 목록 오퍼레이션
- [수동 실행 예제](../../../../../test/java/mdt/ext/rck/RCKSimulationManualRun.java): 실제 서버에 접속하는 개발용 프로그램. 실행 전 코드의 서버·공정·레이아웃 값을 변경한다.
- 자동 테스트: `src/test/java/mdt/ext/rck`의 상태 전이, 진행 상황 파싱, 오퍼레이션, 출력 갱신 테스트

`mdt-instance` 프로젝트 루트에서 관련 자동 테스트를 실행한다.

```bash
gradle :test --tests 'mdt.ext.rck.*' --console=plain
```

수동 실행 프로그램은 자동 테스트와 구분한다. 실제 시뮬레이터와 MDT 서버의 연결 가능 여부는 사용하는 환경에서 별도로 확인해야 한다.
