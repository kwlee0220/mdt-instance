# mdt 루트 패키지

`mdt` 루트 패키지에는 MDT 인스턴스의 실행 진입점과, 여러 하위 패키지가 함께 쓰는 기반 클래스가 있다.
요소 위치 표현, FA³ST 요청을 통한 요소 읽기·쓰기, 전역 설정 조회, 외부 연결 설정 참조, MQTT 연결 공유가 여기에 해당한다.

인스턴스를 실행하고 설정하는 방법은 [프로젝트 README](../../../../README.md)에 있다.
이 문서는 루트 패키지의 클래스가 어떻게 동작하는지와, 하위 패키지에서 이를 쓸 때 지켜야 할 규칙을 다룬다.

## 목차

1. [구성](#1-구성)
2. [실행 진입점](#2-실행-진입점)
3. [요소 위치 표현](#3-요소-위치-표현)
4. [FA³ST 요청으로 요소 읽기·쓰기](#4-fa³st-요청으로-요소-읽기쓰기)
5. [전역 설정 파일](#5-전역-설정-파일)
6. [연결 설정 참조](#6-연결-설정-참조)
7. [MQTT 연결 공유](#7-mqtt-연결-공유)
8. [RobotTwin 접속 정보](#8-robottwin-접속-정보)
9. [알려진 제약](#9-알려진-제약)
10. [관련 문서](#10-관련-문서)

## 1. 구성

| 클래스 | 역할 |
| --- | --- |
| [MDTInstanceMain](MDTInstanceMain.java) | 실행 진입점. 명령행과 `config.json`을 해석해 FA³ST 서비스를 구성하고 시작한다. |
| [ElementLocation](ElementLocation.java), [ElementLocations](ElementLocations.java) | 설정에 적는 요소 위치 표현의 인터페이스와 파서 |
| [DefaultElementLocation](DefaultElementLocation.java) | `<submodelIdShort>:<경로>` 형식 |
| [MDTParameterLocation](MDTParameterLocation.java) | `param:<파라미터>` 형식 |
| [MDTOperationArgumentLocation](MDTOperationArgumentLocation.java) | `oparg:<submodelIdShort>:<INPUT\|OUTPUT>:<인자>` 형식 |
| [FaaastRuntime](FaaastRuntime.java) | 요소 읽기·쓰기를 FA³ST 요청으로 실행 |
| [ElementValueUpdater](ElementValueUpdater.java), [ElementUpdateException](ElementUpdateException.java) | 요소 하나의 값을 반복해서 갱신하는 객체와 그 실패 예외 |
| [MDTGlobalConfigurations](MDTGlobalConfigurations.java) | 전역 설정 파일 `mdt_global_config.json`에서 이름 붙은 연결 설정 조회 |
| [ConnectionConfigRef](ConnectionConfigRef.java) | 설정의 연결 필드("전역 설정 이름" 또는 "인라인 객체")를 나타내는 참조 |
| [MqttServicePool](MqttServicePool.java) | 같은 broker에 대한 MQTT 연결을 여러 사용자가 공유 |
| [RobotTwinConfig](RobotTwinConfig.java) | RobotTwin REST API 접속 정보 |
| [MDTServiceConfigUtils](MDTServiceConfigUtils.java) | FA³ST 기본 서비스 설정을 만드는 도우미. 현재 어디에서도 쓰지 않는다. |

하위 패키지는 다음과 같다. 각 패키지의 상세 내용은 해당 README를 본다.

| 패키지 | 내용 |
| --- | --- |
| `config` | 인스턴스 설정 DTO(`MDTInstanceConfig`)와 FA³ST 서비스 확장(`MDTService`) |
| `persistence` | 영속성 스택, 자산 변수(`asset`), 시계열(`timeseries`) |
| `assetconnection` | AAS Operation 호출을 Java·Program·Script·HTTP 구현으로 연결 |
| `endpoint` | Manager 상태 확인·재등록, MQTT 수신, 동반 프로그램 |
| `ksx9101` | KS X 9101 제조 데이터 JPA 매핑 |
| `ext` | 특정 과제용 연산 구현(RCK 프레스 시뮬레이션 등) |

## 2. 실행 진입점

`MDTInstanceMain`은 picocli 명령으로, 홈 디렉터리(`--home`, 없으면 현재 디렉터리)를 기준으로 다음 일을 한다.

1. `config.json`을 읽고 변수를 치환해 인스턴스 id, 접속 endpoint, 포트, Manager URL을 정한다.
2. 전역 설정 파일과 키 저장소를 정한다.
3. `model.json`을 읽어 Submodel idShort 색인(`MDTModelLookup`)을 만든다.
4. 영속성 스택, 엔드포인트, 연산 제공자를 담은 FA³ST `ServiceConfig`를 만들어 현재 디렉터리의 `config_faaast.json`에 기록한다.
5. FA³ST 서비스를 시작한다. `--type jar`이면 표준 출력에 `[***MARKER***] MDTInstance started: <endpoint>`를 찍는다.

명령행 옵션, 환경 변수, 값이 정해지는 순서는 [프로젝트 README의 5장과 6장](../../../../README.md#5-실행)에 정리되어 있다.
`[***MARKER***]` 문구는 mdt-manager가 시작 성공을 판단하는 기준이므로 바꾸면 안 된다.

## 3. 요소 위치 표현

자산 변수의 `element`, MQTT 구독 대상처럼 설정에서 인스턴스 안의 요소를 가리킬 때는 문자열 위치 표현을 쓴다.
`ElementLocations.parseStringExpr()`가 앞부분을 보고 형식을 고른다.

| 형식 | 예 | 가리키는 요소 |
| --- | --- | --- |
| `<submodelIdShort>:<idShortPath>` | `Telemetry:Measurements.Temperature` | 해당 Submodel의 경로 |
| `param:<파라미터>` | `param:Temperature` | Data Submodel의 해당 파라미터 값(`…ParameterValues[i].ParameterValue`) |
| `oparg:<submodelIdShort>:<INPUT\|OUTPUT>:<인자>` | `oparg:Simulation:INPUT:Speed` | AI·Simulation Submodel의 입출력 값 |

주의할 점은 다음과 같다.

- **앞부분은 Submodel의 idShort다.** 전역 ID URI가 아니며, MDT 표현식과 달리 인스턴스 id도 넣지 않는다(`param:machine01:Temperature`가 아니라 `param:Temperature`).
- **경로 표기:** 하위 요소는 `.`, List 원소는 `[0]`처럼 쓴다.
- **`param:` 뒤에 쓸 수 있는 것:** 정수이면 파라미터 인덱스로 쓴다. 그렇지 않으면 첫 `.`(없으면 첫 `[`) 앞부분을 `ParameterID`로 찾고, 나머지를 그 값 아래의 하위 경로로 붙인다. 예를 들어 `param:Speed.Max`는 `Speed` 파라미터 값의 `Max` 요소다.
- **`oparg`의 종류**는 대문자 `INPUT` 또는 `OUTPUT`이다. Submodel의 semantic ID가 AI나 Simulation이어야 한다.

### 활성화

파싱한 위치는 바로 쓸 수 없고, 먼저 `activate(MDTModelLookup)`으로 실제 Submodel ID와 경로를 정해야 한다.
활성화 전에 `getSubmodelId()`나 `getElementPath()`를 부르면 `IllegalStateException`("not activated")이 난다.
단, `<submodelIdShort>:<경로>` 형식의 `getElementPath()`는 파싱한 경로를 그대로 돌려주므로 활성화 전에도 값이 나온다.

| 형식 | 활성화에서 하는 일 | 실패하는 경우 |
| --- | --- | --- |
| 일반 | idShort로 Submodel을 찾아 ID를 기록 | 해당 idShort의 Submodel이 없음 |
| `param:` | Data Submodel에서 `ParameterID`로 인덱스를 찾아 경로 구성 | Data Submodel이 없음, 파라미터가 없음 |
| `oparg:` | semantic ID로 AI·Simulation을 구분해 경로 구성 | Submodel이 없음, semantic ID가 AI·Simulation이 아님 |

활성화는 모델에 요소가 실제로 있는지 끝까지 확인하지는 않는다. 일반 형식은 Submodel만 찾으므로, 경로가 틀리면 그 위치를 처음 읽거나 쓸 때 실패한다.
`toIdentifier()`는 활성화된 위치를 FA³ST의 `SubmodelElementIdentifier`로 바꾼다.

## 4. FA³ST 요청으로 요소 읽기·쓰기

인스턴스 안에서 요소를 읽거나 쓸 때는 FA³ST의 `Persistence`를 직접 다루지 않고 `FaaastRuntime`을 거친다.
`FaaastRuntime`은 읽기·쓰기를 FA³ST 요청(`GetSubmodelElementByPathRequest` 등)으로 만들어 `ServiceContext.execute()`로 실행한다.
그래서 외부 REST 요청과 똑같이 영속성 스택의 모든 계층(동시성 제어, 자산 변수, 시계열)을 지난다.

`FaaastRuntime.getOrCreate(serviceContext)`로 얻는다. 운영 환경에는 서비스가 하나뿐이므로 객체 하나를 함께 쓴다.

| 메소드 | 동작 | 실패 처리 |
| --- | --- | --- |
| `getSubmodelElementOfLocation(loc)`, `getSubmodelElementByPath(id, path)`, `getSubmodelElementByReference(ref)` | 요소 읽기 | 응답 상태를 검사하지 않는다(아래 참고). |
| `putSubmodelElementByPath(id, path, element)` | 요소 전체 교체 | 오류 응답이면 `ElementUpdateException` |
| `setSubmodelElementValueByPath(id, path, valueJson)` | value-only JSON으로 값 갱신 | 오류 응답이면 `ElementUpdateException` |
| `updateSubmodelElementValue(id, path, element)` | 요소 객체의 값으로 갱신 | 오류 응답이면 `ElementUpdateException` |
| `newValueUpdater(loc)` | 같은 요소를 반복해서 갱신할 `ElementValueUpdater` 생성 | 요소가 없거나 타입을 읽지 못하면 `ElementUpdateException` |

FA³ST의 `execute()`는 요청 처리 중 생긴 오류(값 파싱 실패, 요소 없음, 저장 실패)를 예외로 던지지 않고 오류 상태의 응답으로 돌려준다.
쓰기 메소드는 이 응답 상태를 확인해 실패를 `ElementUpdateException`으로 바꾸며, 예외에는 FA³ST의 상태 코드와 메시지가 담긴다.
읽기 메소드는 상태를 확인하지 않고 응답의 payload를 그대로 돌려주므로, 실패하면 예외 대신 `null`이 나올 수 있다.

### 반복 갱신

MQTT 메시지처럼 같은 요소를 여러 번 갱신할 때는 `newValueUpdater(loc)`로 갱신기를 만들어 둔다.

```java
ElementValueUpdater updater = FaaastRuntime.getOrCreate(serviceContext).newValueUpdater(location);
updater.update("23.5");      // 숫자 Property
updater.update("\"idle\"");  // 문자열 Property
```

갱신기는 만들 때 요소의 타입 정보를 한 번 조회해 두고, 갱신할 때마다 다시 조회하지 않는다.
요소가 없으면 갱신기를 만드는 단계에서 실패하므로, 설정 오류를 시작할 때 발견할 수 있다.
`update()`에는 요소 타입에 맞는 AAS value-only JSON을 넘긴다.

## 5. 전역 설정 파일

DB·MQTT broker처럼 여러 인스턴스가 함께 쓰는 연결 정보는 전역 설정 파일에 이름을 붙여 두고, 인스턴스 설정에서는 이름으로 참조한다.
파일 형식과 그룹별 예는 [프로젝트 README 6.5절](../../../../README.md#65-전역-설정-파일)에 있다.

`MDTInstanceMain`은 다음 순서로 처음 발견한 파일을 쓴다.

1. 명령행 `--globalConfig`(지정했는데 파일이 없으면 시작 실패)
2. `config.json`의 `globalConfigFile`(지정했는데 파일이 없으면 시작 실패)
3. 홈 디렉터리의 `mdt_global_config.json`
4. 환경 변수 `MDT_GLOBAL_CONFIG_FILE`(파일이 있을 때만)

어느 것도 없으면 경고만 남기고 시작한다. 이 경우 전역 설정을 조회하는 순간 `IllegalStateException`이 난다.

`MDTGlobalConfigurations`의 조회 규칙은 다음과 같다.

| 그룹 | 조회 메소드 | 결과 타입 |
| --- | --- | --- |
| `jdbcs` | `getJdbcConfig(name)` | `JdbcConfiguration` |
| `mqttBrokers` | `getMqttBrokerConfig(name)` | `MqttBrokerConfig` |
| `opcuas` | `getOpcUaConfig(name)` | `OpcUaConnectionConfig` |
| `robotTwins` | `getRobotTwinConfig(name)` | `RobotTwinConfig` |
| `persistence` | `loadJpaConfiguration()` | JPA 설정. `ksx9101`만 쓴다. |

- **이름 찾기:** 이름이 정확히 같은 항목을 쓴다. 없고 이름이 `default`(대소문자 무시)이면 그룹의 첫 항목을 쓴다. 그 밖에는 `ResourceNotFoundException`이다.
- **매번 다시 읽음:** 조회할 때마다 파일을 다시 읽는다. 시작할 때 한 번 조회해 결과를 보관하는 것이 원칙이다.
- **변수 치환:** 고른 항목 안의 `${env:이름}`, `${sys:이름}`을 치환한다. 접두어 없는 `${이름}`은 정의된 변수가 없으므로 조회가 실패한다. `config.json`과 달리 `${MDT_INSTANCE_ID}` 같은 인스턴스 변수도 쓸 수 없다.
- **비밀 정보:** 이 파일에는 DB 비밀번호 같은 비밀 정보가 들어가므로 저장소에 커밋하지 않는다.

## 6. 연결 설정 참조

자산 변수와 로봇 연산의 연결 필드(`jdbcConfig`, `mqttBrokerConfig`, `robotTwinConfig` 등)는 전역 설정 이름이나 연결 설정 객체 중 하나를 받는다.
`ConnectionConfigRef`가 이 필드를 나타낸다.

```json
"jdbcConfig": "factory"                                    // 전역 설정 jdbcs.factory
"jdbcConfig": { "jdbcUrl": "jdbc:postgresql://..." }       // 인라인 연결 설정
```

| 입력 | 해석 |
| --- | --- |
| 문자열 | 전역 설정 항목 이름 |
| 객체 | 인라인 연결 설정 |
| 필드 생략 또는 `null` | 전역 설정 `default` |
| 이전 형식 필드(예: `jdbcConfigName`) | 전역 설정 이름. 새 필드와 함께 쓰면 설정 로딩이 실패한다. |

`resolve(globalLookup)`는 인라인 설정이면 그대로, 이름이면 전역 설정을 조회해 돌려준다.
로그에는 `toDisplayString()`을 쓴다. 이름이면 `jdbcs.factory`처럼 출력하고, 인라인 설정은 호출자가 넘긴 함수로 출력하므로 비밀번호가 찍히지 않게 함수를 작성해야 한다.
`serialize()`는 받은 형태(이름 또는 객체) 그대로 기록하며, 인라인 설정의 값이 `null`인 필드는 기록하지 않는다.

## 7. MQTT 연결 공유

MQTT 자산 변수와 MQTT 수신 엔드포인트는 broker 연결을 `MqttServicePool`로 공유한다.
broker URL, 연결 timeout, keep-alive 주기, 재연결 간격이 모두 같으면 같은 연결을 쓴다.

```java
try ( MqttServicePool.Lease lease = MqttServicePool.acquire(brokerConfig) ) {
    MqttService mqtt = lease.getService();
    // 구독·발행
}
```

- **연결 생성:** 처음 획득할 때 연결을 만들어 시작한다. 연결은 비동기로 이루어지므로 획득 직후에 연결이 끝났다는 보장은 없다.
- **연결 해제:** 마지막 `Lease`를 반납하면 연결을 중지하고 최대 5초 기다린다. `close()`는 여러 번 불러도 한 번만 반납한다.
- **지켜야 할 규칙:** 공유 서비스이므로 사용자가 직접 시작하거나 중지하면 안 된다. 구독한 topic은 `Lease`를 반납하기 전에 직접 해제한다. `MqttService.subscribe()`가 돌려주는 `Subscription`을 닫으면 해제된다.
- **실패한 연결:** 실패한 연결은 ERROR 로그를 남기고, 다음 `acquire()`에서 새 연결로 바뀐다. 이미 그 연결을 쥐고 있던 사용자는 자동으로 옮겨 가지 않는다.

## 8. RobotTwin 접속 정보

`RobotTwinConfig`는 RobotTwin REST API 접속 정보로, `robotTwinBaseUrl`과 `twinId` 두 필드를 가진다.
요청 경로는 `<robotTwinBaseUrl>/api/v1/robot_twins/<twinId>/...`이다.
전역 설정 `robotTwins`의 항목이나, RobotTwin 자산 변수·로봇 연산의 `robotTwinConfig` 인라인 객체로 쓴다.

## 9. 알려진 제약

| 제약 | 영향 |
| --- | --- |
| 위치 표현은 `:`로 나누며 이스케이프가 없다. | idShort나 경로에 `:`가 들어간 요소는 가리킬 수 없다. |
| `oparg:`는 인자 이름으로 요소를 고르지 않는다. | 경로가 종류(`INPUT`/`OUTPUT`)로만 정해져 `AIInfo.Inputs.InputValue`처럼 인덱스 없는 경로가 된다. 특정 인자를 가리키려면 일반 형식으로 실제 경로를 적는다. |
| `oparg:`의 필드가 모자라면 친절한 오류가 나지 않는다. | `oparg:Simulation:INPUT`처럼 인자를 빠뜨리면 배열 범위 오류가 난다. 종류를 소문자로 쓰면 `IllegalArgumentException`이다. |
| `FaaastRuntime`의 읽기 메소드는 응답 상태를 검사하지 않는다. | 실패하면 예외 대신 `null`이 돌아올 수 있다. |
| 전역 설정은 조회마다 파일을 다시 읽는다. | 자주 조회하는 경로에서 부르면 파일 I/O가 반복된다. |
| `MDTServiceConfigUtils`, `FaaastRuntime.getExecutor()`는 쓰는 곳이 없다. | 지워도 동작에 영향이 없다. |

## 10. 관련 문서

| 문서 | 내용 |
| --- | --- |
| [프로젝트 README](../../../../README.md) | 실행 방법, 명령행 옵션, 인스턴스 설정 파일, 전역 설정 파일 |
| [mdt.persistence.asset](persistence/asset/README.md) | 자산 변수의 `element` 위치와 연결 설정 |
| [mdt.endpoint](endpoint/README.md) | MQTT 구독 대상 위치, Manager 연결 |
| [mdt.assetconnection](assetconnection/README.md) | 연산 제공자와 실행 계약 |
| [mdt.persistence.timeseries](persistence/timeseries/README.md) | 시계열 Submodel |
| [mdt.ext.rck](ext/rck/README.md) | RCK 프레스 시뮬레이터 연동 |
