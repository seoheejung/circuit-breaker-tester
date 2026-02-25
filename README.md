# EXIT8 – Backend Spring (Load & Observability Playground)

>Spring Boot 기반 단일 API 서비스에서   
>의도적 부하, 서킷 브레이커, 관측(Observability)을 테스트하기 위한 백엔드 프로젝트

---

## 목차

1. [프로젝트 목적](#1-프로젝트-목적)
2. [기술 스택](#2-기술-스택)
3. [디렉토리 구조](#3-디렉토리-구조)
4. [실행 환경 분리 전략](#4-실행-환경-분리-전략)
5. [데이터 계층 구성](#5-데이터-계층-구성)
6. [API 분류](#6-api-분류)
7. [Service 책임 원칙](#7-service-책임-원칙)
8. [공통 응답 포맷](#8-공통-응답-포맷)
9. [부하 테스트 API](#9-부하--테스트-api)
10. [관측 아키텍처](#10-관측-아키텍처)
11. [로깅 전략](#11-로깅-전략)
12. [Docker 기반 개발 환경](#12-docker-기반-개발-환경)
13. [Vault 연동](#13-Vault-연동)
14. [테스트 불변식](#14-테스트-불변식)
15. [앞으로의 확장 / 완료 현황 정리](#15-앞으로의-확장--완료-현황-정리)

---

## 1. 프로젝트 목적

이 프로젝트는 단순 CRUD API가 아니라, 
1. 의도적으로 시스템 부하를 발생시키고
2. 서킷 브레이커가 언제 동작하는지 확인하며
3. Prometheus(Grafana 연계 전제)로 상태를 시각화
4. Docker 단일 서버 환경에서의 한계를 체험

하는 것을 목적으로 한다.

> ⚠️ 성능 최적화가 목적이 아니고, “시스템이 망가지기 직전 어떤 일이 벌어지는지”를 관측하는 테스트용 백엔드

---

## 2. 기술 스택
| 구분            | 기술                               |
| ------------- | -------------------------------- |
| Language      | Java 17                          |
| Framework     | Spring Boot **3.4.2**            |
| Build         | Gradle                           |
| DB            | PostgreSQL 16                    |
| Cache         | Redis 7                          |
| Resilience    | Resilience4j                     |
| Observability | Actuator, Micrometer, Prometheus |
| Infra         | Docker (단일 서버 기준)                |

---

## 3. 디렉토리 구조
```
services/service-a/backend/
├── build.gradle
├── .env
├── src/main/java/com/exit8/
│   ├── Application.java
│   │
│   ├── controller/        # API 진입점
│   │   ├── SystemHealthController.java
│   │   ├── LoadScenarioController.java
│   │   └── CircuitBreakerTestController.java
│   │
│   ├── service/           # 시스템 동작 로직 (부하 생성 · 차단 제어 · 상태 계산)
│   │   ├── SystemHealthService.java # 토글 + snapshot 반영
│   │   ├── LoadScenarioService.java # 캐시 적용 로직 추가
│   │   └── CircuitBreakerTestService.java
│   │
│   ├── repository/        # 테스트용 데이터 접근
│   │   ├── LoadTestLogRepository.java
│   │   ├── DummyDataRepository.java
│   │   └── SystemLogRepository.java 
│   │
│   ├── domain/            # 부하/로그 도메인
│   │   ├── LoadTestLog.java
│   │   ├── DummyDataRecord.java
│   │   └── SystemLog.java
│   │
│   ├── dto/
│   │   ├── DefaultRequest.java
│   │   ├── DefaultResponse.java
│   │   ├── ErrorResponse.java
│   │   ├── SystemSnapshot.java
│   │   ├── ToggleResponse.java
│   │   └── SystemHealthStatus.java # redisCacheEnabled / hitRatio 추가
│   │
│   ├── exception/         # 공통 예외 처리
│   │   ├── ApiException.java
│   │   └── GlobalExceptionHandler.java
│   │
│   ├── filter/            # HTTP 진입 trace_id 생성
│   │   └── TraceIdFilter.java          # 요청 식별
│   │   ├── RateLimitFilter.java        # 차단 로직
│   │   └── ClientIpResolver.java       # IP 해석 책임 분리
│   │ 
│   ├── logging/           # AOP 기반 관측 로깅
│   │   ├── LogAspect.java                  # LOAD_START / END / FAIL 트리거
│   │   ├── LogLevelPolicy.java             # INFO / WARN / ERROR 판단
│   │   ├── LogEvent.java                   # 이벤트 도메인 (LOAD_START 등)
│   │   └── TraceContext.java               # MDC 기반 trace_id 접근 전용
│   │
│   ├── state/
│   │   └── RuntimeFeatureState.java        # 상태 저장소
│   │
│   ├── observability/     # 실시간 관측 이벤트 및 메모리 버퍼 (프론트 대시보드용)
│   │   ├── RequestEvent.java
│   │   ├── RequestEventBuffer.java
│   │   └── CacheMetrics.java               # hit/miss counter
│   │
│   └── config/
│       ├── datasource/                     # DB 설정
│       │   └── PostgresConfig.java
│       ├── redis/                          # redis 설정
│       │   └── RedisConfig.java
│       ├── observability/                  # Metrics / tracing 설정
│       │   └── MetricsConfig.java 
│       ├── constants/
│       │   └── CircuitNames.java
│       └── filter/                         # Filter 설정
│           └── FilterOrderConfig.java      # 순서 제어
│
└── src/main/resources/
    ├── application.yml
    ├── application-local.yml
    ├── application-docker.yml
    └── logback-spring.xml                   # JSON 구조 로그 출력

```

---

## 4. 실행 환경 분리 전략
### Profiles
- local : 로컬 개발 (DB/Redis는 Docker 컨테이너)
- docker : GCP 내부 Docker 네트워크

```
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```
- Gradle `bootRun` 기본 프로필은 `local`
- 실행 환경 구분은 `SPRING_PROFILES_ACTIVE` 기준
- Vault 연동 여부와 관계없이 프로필 전략은 동일
- Vault 접근 정보는 실행 시점에 환경 변수로 주입
  (`VAULT_URI`, `VAULT_TOKEN`)

---

## 5. 데이터 계층 구성
### PostgreSQL

- 부하 테스트 데이터는 운영 도메인과 분리
- JPA 사용
- ddl-auto: update (테스트 단계)

| 테이블                       | 목적            |
| ------------------------- | ------------- |
| spring_load_test_logs     | 부하 테스트 결과     |
| spring_dummy_data_records | DB 부하용 더미 데이터 |
| spring_logs               | 시스템 로그 (후순위)  |

### Redis
- Read-Through Cache 기반 부하 분산 테스트
- `/db-read` 시나리오에 한정하여 적용
- TTL 기반 자연 만료 전략 사용
  - Redis TTL: 5분 (테스트 시간은 TTL 이하로 제한)

#### Key 네이밍 규칙
```
{service}:{app}:{resource}:{id}

# 예시
service-a-backend:spring:load:cpu
service-a-backend:spring:health:status
```
- 모든 Key는 TTL 필수
- 테스트용 Key는 test: prefix 사용

> ※ Redis 실패도 Circuit 실패에 포함된다.

---

## 6. API 분류

### Load & Experiment APIs
- 시스템에 의도적 부하를 가하는 API
- 모든 호출은 상태 변화를 유발함
- side-effect 존재 
  - CPU 사용률 변화
  - DB Connection Pool 점유
  - CircuitBreaker 상태 변화
  - Metric 증가
  - 로그 이벤트 발생

> 테스트 목적의 API이며 운영 read-only API와 명확히 구분한다.

### Health & Observability APIs
- 시스템 현재 상태 조회 API
- UI / Prometheus / 운영 모니터링 연계
- 기본적으로 read-only
- 비즈니스 상태를 변경하지 않음
- 단, 예외적으로:
  - `/api/system/rate-limit/toggle`,  `/api/system/redis-cache/toggle` API는 테스트 제어용 관리며 상태를 변경한다.
  - 운영 Health API와는 성격이 다름

---

## 7. Service 책임 원칙

### 기본 원칙
- Controller는 얇게 유지 → 요청 파라미터 바인딩 + Service 호출만 담당
- 모든 검증·제어는 Service 담당
- checked exception 외부 전파 ❌
  - Service 내부에서 정제 후 ApiException 변환
- 부하 시나리오 = Service 책임
- 관측 데이터 접근(RequestEventBuffer 등)도 반드시 Service를 통해 접근

<br>

### UI ↔ API ↔ Service 정합성 원칙
- UI 버튼 · API 엔드포인트 · Service 메서드가 1:1로 매핑

| UI 버튼            | API                                         | Service                                           |
| ---------------- | ------------------------------------------- | ------------------------------------------------- |
| CPU 부하 시작        | `/api/load/cpu`                             | `LoadScenarioService.generateCpuLoad()`           |
| DB 부하 시작         | `/api/load/db-read`<br>`/api/load/db-write` | `simulateDbReadLoad()`<br>`simulateDbWriteLoad()` |
| 상태 표시            | `/api/system/health`                        | `SystemHealthService.getCurrentStatus()`          |
| Snapshot 표시      | `/api/system/snapshot`                      | `SystemHealthService.getSnapshot()`               |
| 최근 요청 피드         | `/api/system/recent-requests`               | `SystemHealthService.getRecentRequests()`         |
| RateLimit ON/OFF | `/api/system/rate-limit/toggle`             | `SystemHealthService.toggleRateLimit()`           |
| 차단 테스트           | `/api/circuit/test`                         | `CircuitBreakerTestService.callWithDelay()`       |

### UI ↔ API ↔ Service 정합성 원칙
- UI 버튼 · API 엔드포인트 · Service 메서드가 1:1로 매핑

| UI 버튼           | API                              | Service                                     |
| --------------- | -------------------------------- | ------------------------------------------- |
| CPU 부하 시작       | `/api/load/cpu`                  | `LoadScenarioService.generateCpuLoad()`     |
| DB READ 부하 시작   | `/api/load/db-read`              | `simulateDbReadLoad()`                      |
| DB WRITE 부하 시작  | `/api/load/db-write`             | `simulateDbWriteLoad()`                     |
| Redis Warm-up | `/api/load/redis/warmup`         | `simulateDbReadLoad(500)`                   |
| 상태 표시           | `/api/system/health`             | `SystemHealthService.getCurrentStatus()`    |
| Snapshot 표시     | `/api/system/snapshot`           | `SystemHealthService.getSnapshot()`         |
| 최근 요청 피드        | `/api/system/recent-requests`    | `SystemHealthService.getRecentRequests()`   |
| RateLimit ON/OFF | `/api/system/rate-limit/toggle`  | `SystemHealthService.toggleRateLimit()`     |
| Redis ON/OFF    | `/api/system/redis-cache/toggle` | `SystemHealthService.toggleRedisCache()`    |
| 차단 테스트          | `/api/circuit/test`              | `CircuitBreakerTestService.callWithDelay()` |

<br>

### 규칙 강제 이유
1. 관측 가능성(Observability)
    - 로그, 메트릭, 이벤트가 Service 단위로 정확히 분리됨
    - AOP 로깅과 완벽하게 정렬됨
    - 요청 흐름 추적이 명확해짐
2. 부하 시나리오 단순화
    - “어떤 버튼이 어떤 부하를 만들었는지” 즉시 추적 가능
    - 테스트 결과 재현성 확보
3. UI ↔ 백엔드 커뮤니케이션 명확화
    - API 계약이 Service 단위로 고정됨
    - 프론트/백엔드/인프라 간 오해 제거
4. AOP 로깅 구조와 완벽하게 호환
    - Service.*(..) 기준으로 로깅 (실행 시간 측정, 성공/실패 판단, 이벤트 타입 분류)
    - Filter / Infra 레이어는 별도 관측 지점으로 분리됨

### Observability 현재 구조
```
[Filter Layer]
  ├─ TraceIdFilter
  ├─ RateLimitFilter  ← 이벤트 발생 지점 1

[Service Layer]
  ├─ LoadScenarioService
  ├─ Redis Cache (Read-Through)
  ├─ SystemHealthService
  ├─ CircuitBreakerTestService

[Resilience Layer]
  ├─ Resilience4j CircuitBreaker (AOP Proxy)   ← 이벤트 발생 지점 2

[Exception Layer]
  ├─ GlobalExceptionHandler  ← CIRCUIT_OPEN 이벤트 기록 지점
  
[Observability Layer]
  ├─ CacheMetrics (hit/miss/error)
  ├─ Micrometer Counter / Timer
  ├─ RequestEventBuffer (in-memory ring buffer)

[API Layer]
  ├─ SystemSnapshot API
  ├─ RecentRequests API
  └─ RateLimit Toggle API
```

---

## 8. 공통 응답 포맷
```
{
  "httpCode": 200,
  "data": {},
  "error": null
}

```
- httpCode : 실제 HTTP 상태 코드와 동일
- data : 성공 시 결과 데이터 (없으면 null 또는 {})
- error : 성공 시 항상 null

<br>

에러 시:
```
{
  "httpCode": 500,
  "data": null,
  "error": {
    "code": "INVALID_PARAM",
    "message": "durationMs max is 10000"
  }
}
```
- data : 에러 시 항상 null
- error.code : 비즈니스 에러 코드 (고정 문자열)
- error.message : 클라이언트 전달용 메시지

| 상황                   | 분류             |
| -------------------- | -------------- |
| Circuit OPEN         | 503            |
| DB 병목                | 200 + DEGRADED |
| 잘못된 파라미터             | 400            |

---

## 9. 부하 테스트 API

### SystemHealthController
- UI 빠른 상태 확인용
- 상태 판단 기준
```
UP
- CircuitBreaker 상태: CLOSED
- Hikari Pool waitingThreads == 0

DEGRADED
- CircuitBreaker 상태: HALF_OPEN
- Hikari Pool waitingThreads > 0
- idle == 0 && waiting > 0 (풀 고갈 조짐)

DOWN
- CircuitBreaker 상태: OPEN
- 핵심 기능 차단 상태

```

<br>

### LoadScenarioController
- POST만 사용
- 모든 결과는 spring_load_test_logs 저장
- 테스트용이므로 상한값 강제 적용 (10_000)

#### 1) CPU 부하
- CPU saturation 상황 재현
- GC / 응답 지연 / CircuitBreaker 영향 관측
- sleep ❌ (스레드 블로킹만 발생)
- busy-loop 연산으로 실제 CPU 점유
- `durationMs <= 10000` 초과 시 ApiException

#### 2) DB READ 부하
- DB 커넥션 풀 고갈
- SELECT 병목 상황 관측
- DummyData 반복 SELECT
- 캐시 적용 여부는 feature.redis-cache.enabled 토글로 제어
- **repeatCount 만큼 findById / findAll 반복**

#### 3) DB WRITE 부하
- WAL 증가
- 트랜잭션 누적 상황 재현
- DummyData INSERT 반복
- flush 전략은 기본값 유지 (추후 변경 가능)

<br>

### CircuitBreakerTestController
- CircuitBreaker가 언제 OPEN 되는지 눈으로 확인

#### 동작 방식
1. 내부에서 의도적으로 timeout 발생
2. Resilience4j가 실패 누적
3. OPEN 전환
4. CallNotPermittedException 발생
5. GlobalExceptionHandler에서 503 반환

---

## 10. 관측 아키텍처

### RateLimit
- IP 기반 1차 방어 레이어
- 반복적 부하를 차단하고 차단 이벤트 기록
- application.yml 설정 기반으로 초기 상태 결정
  - 서버 재시작 시에는 application.yml 설정 값으로 초기화
- 런타임 toggle API를 통해 변경 가능

### CircuitBreaker
- 2차 방어 레이어
- 내부 임계치 초과 시 OPEN 상태로 전환하여 시스템 보호
- OPEN 상태에서는 CallNotPermittedException이 즉시 발생
  - 해당 예외는 GlobalExceptionHandler에서 503으로 변환

### Prometheus 메트릭 노출
- Actuator + Micrometer 기반 메트릭 수집
- Grafana에서 시계열 분석 수행

### SystemSnapshot API
- 프론트엔드 상단 상태 표시를 위한 단일 데이터 소스
- Circuit 상태, DB Pool 상태, 평균 응답 시간 등

### Recent Requests API
- IP별 요청 이벤트를 구조화하여 제공
- 200 / 429 / 503 상태 기반 이벤트 피드

### 아키텍처 레벨
``` 
Client
  ↓
RateLimit
  ↓
Service
  ├─ Redis (READ)
  ├─ DB (READ / WRITE)
  ↓
Repository (순수 DB)
  ↓
Resilience4j CircuitBreaker (AOP Proxy)
  ↓
Observability (Metrics + Logs + Snapshot)
```

- RateLimit: 트래픽 양을 제어하는 변수
- Redis: DB 부하 밀도를 제어하는 변수

---

## 11. 로깅 전략 
### 원칙
- trace_id 기반 요청 추적
- 비즈니스 로직 로그는 Service / Controller에 두지 않음
- 흐름 로깅은 AOP로 일원화
- 인프라 이벤트(CircuitBreaker 등)는 예외적으로 Service에서 기록

<br>

### MDC + AOP를 사용하는 이유
- 로그를 요청 단위(trace_id) 로 자동 묶기 위함     
   → 각 로그 호출마다 trace_id를 전달하지 않아도 됨
- 비즈니스 로직(Service)과 로깅 관심사를 분리하기 위함
   → 로깅 코드가 서비스 로직을 오염시키지 않음
- 실행 시간, 성공/실패, 이벤트 타입을
   모든 Service 메서드에 동일한 기준으로 적용하기 위함
- 로그 포맷·레벨·필드를 중앙에서 통제하여
   운영 환경에서도 일관된 분석이 가능하도록 하기 위함

#### LogAspect
- Service 메서드 단위 실행 흐름 로깅
- LOAD_START → LOAD_END / LOAD_FAIL / LOAD_ERROR
- 실행 시간(duration) 측정
- 모든 로그는 trace_id 기준으로 연결

#### TraceContext
- MDC 기반 trace_id 조회 전용
- Service / AOP / Exception에서 공통 사용
- trace_id 생성 책임 없음

#### LogLevelPolicy
- Circuit OPEN → WARN
- DB / I/O → ERROR
- 추후 CRITICAL 확장 가능

#### TraceIdFilter (요청 단위 추적의 시작점)
- HTTP 요청 진입 시 trace_id 생성
- 외부 요청에 X-Trace-Id 헤더가 존재할 경우 재사용
- MDC에 trace_id 저장하여 전 구간 자동 전파
- 요청 종료 시 MDC clear로 쓰레드 오염 방지

#### LogEvent (이벤트 표준화)
- 로그 메시지에 포함될 이벤트 타입 상수 정의
- 이벤트 기준으로 로그 집계 및 분석 가능

  | Event               | 의미                  |
  | ------------------- |---------------------|
  | LOAD_START          | Service 실행 시작       |
  | LOAD_END            | 정상 종료               |
  | LOAD_FAIL           | 비치명적 실패             |
  | LOAD_ERROR          | 치명적 실패              |
  | CIRCUIT_OPEN        | CircuitBreaker OPEN |
  | UNHANDLED_EXCEPTION | AOP 밖 예외            |
  | RATE_LIMITED        | RateLimit에 의해 요청 차단 |
  | BUSINESS_EXCEPTION  | ApiException 발생 (비즈니스 예외) |


<br>

#### GlobalExceptionHandler (최종 방어 로깅)
- AOP 범위를 벗어난 예외에 대해 반드시 로그 기록
- trace_id 기준으로 “로그 없는 장애” 방지
- 비즈니스 예외와 시스템 예외 로그 레벨 분리

<br>

#### RateLimitFilter
- TraceIdFilter 이후 실행
- 차단 요청도 trace_id 포함
- Service/AOP로 진입하지 않음
- 관측 이벤트 및 메트릭은 기록됨

```
Request
  → TraceIdFilter
      → trace_id 생성
  → RateLimitFilter
      → 차단/허용 판단
  → Controller
    → Service
```

<br>

### trace_id 전파 검증 (요청 단위 추적 불변식 확인)

- 로그 확인 (trace_id 기준으로 START/END 로그가 모두 존재해야 함)
```
docker exec -it service-a-backend sh -c "grep test-trace-123 /app/logs/app.log"
```

<br>

#### 외부 trace_id가 전달된 요청
1. 시나리오
  - 클라이언트 요청에 X-Trace-Id 헤더가 포함된 경우
  - Filter는 새 trace_id를 생성하지 않고, 전달된 값을 그대로 사용한다.

2. 기대 동작
   - 요청 1건에 대해 동일한 trace_id를 가진 로그가
     - Service 실행 시작 시 1건 (LOAD_START)
     - Service 실행 종료 시 1건 (LOAD_END)
     - 반드시 한 쌍으로 기록되어야 한다.
   - START/END 로그 사이에서 trace_id는 절대 변경되면 안 된다.

3. 요청 1회 호출
```
curl -X POST "http://localhost:8080/api/load/cpu?durationMs=1000" -H "X-Trace-Id: test-trace-123"
```

> ✔ 외부에서 전달된 trace_id(test-trace-123)가 Filter → MDC → AOP → 로그까지 변경 없이 유지됨을 검증한다.

<br>

#### trace_id가 없는 요청
1. 시나리오
  - 클라이언트 요청에 X-Trace-Id 헤더가 없는 경우
  - Filter는 새로운 trace_id를 생성한다.

2. 기대 동작
  - Filter에서 생성된 trace_id가 요청 전 구간에 전파된다.
  - LOAD_START / LOAD_END 로그에 동일한 신규 trace_id가 기록되어야 한다.
  - START와 END 사이에서 trace_id가 달라지면 안 된다.

> ✔ trace_id 미존재 요청에서도 요청 단위 추적이 반드시 보장됨을 검증한다.

---

## 12. Docker 기반 개발 환경

### Spring Boot 애플리케이션 Docker 이미지화
- Gradle 기반 Spring Boot 애플리케이션을 Docker 이미지로 패키징
- 빌드 단계와 실행 단계를 분리한 **Multi-stage 빌드 적용으로 이미지 용량 최소화**
- 최종 이미지에는 실행에 필요한 JAR 파일만 포함
- Build Stage
  - eclipse-temurin:17-jdk-alpine 기반
  - Gradle Wrapper를 사용하여 애플리케이션 빌드
- Runtime Stage
  - 빌드 단계에서 생성된 JAR만 복사하여 실행
  - curl을 설치하여 /actuator/health 기반 Docker Healthcheck 지원
- Docker Healthcheck와 Spring Actuator를 연계한 컨테이너 생존 상태 확인 가능

<br>

### Docker Healthcheck & Spring Actuator 설계 원칙
- docker-compose를 수정하지 않는 것을 전제로 Spring Actuator Health 동작 설계
- docker-compose Healthcheck는 /actuator/health 기준으로 HTTP 응답 가능 여부(Liveness) 만 판단

```
healthcheck:
  test: ["CMD", "wget", "-q", "--spider", "http://localhost:8080/actuator/health"]
```

### Docker 환경 Health 정책 (application-docker.yml)
```
management:
  health:
    db:
      enabled: false
    redis:
      enabled: false
    circuitbreakers:
      enabled: false
```
- DB / Redis / CircuitBreaker 상태는 Health 판단에서 제외
- 부하 테스트 중 의존성 장애가 발생해도 컨테이너 유지
- restart loop 방지
- application.yml에는 health 판단 로직을 두지 않음
- health의 의미는 profile별로 완전히 다르게 정의됨
- docker 환경에서는 관측 정보(state, details)를 노출하지 않음
- liveness 판단과 observability를 명확히 분리하기 위함

| 레이어             | Health 의미            |
| --------------- | -------------------- |
| Docker          | 프로세스 + HTTP 응답 여부    |
| Spring (docker) | Liveness             |
| 관측              | Prometheus / Grafana |

> ※ CircuitBreaker OPEN 상태에서도 Docker Health는 정상으로 판단된다.

---

## 13. Vault 연동
- DB 자격 증명을 애플리케이션 외부로 분리하기 위해 Spring Cloud Vault 사용
- Vault 기반 설정 로딩 구조를 검증

### Spring Cloud Vault Client 연동
- 애플리케이션 기동 시 Vault에서 설정 값을 로드
- Vault KV(v2)를 Spring PropertySource로 통합

### DB 자격 증명 완전 외부화
- spring.datasource.username / password 하드코딩 제거
- Vault에 저장된 db.username, db.password를 명시적으로 매핑

#### 검증 항목
- Spring Cloud Vault Client 기동 시 Vault 연결 
- DB 자격 증명 로딩 성공 / 실패 시 기동 결과

### 의도적으로 제외한 범위
- Secret rotation 
- Vault Auth (AppRole, Kubernetes 등)
- Vault HA / TLS 구성

---

## 14. 테스트 불변식

- CircuitBreaker 설정은 테스트 간 변경하지 않는다.
- Redis TTL은 5분으로 고정한다.
- JMeter Thread / Delay 값은 테스트 단위로 고정한다.
- Docker 자원(CPU/Mem 제한)은 테스트 간 변경하지 않는다.

### CircuitBreaker 실험 설정

- slidingWindowSize: 20 (최근 20개 호출 기준)
- failureRateThreshold: 50% (50% 이상 실패 or 느린 호출이면 OPEN)
- slowCallDurationThreshold: 2s (2초 이상이면 slow call)
- slowCallRateThreshold: 50% (slow call 50% 이상 시 OPEN)
- waitDurationInOpenState: 10s (OPEN 유지 10초)

---

## 15. 앞으로의 확장 / 완료 현황 정리

### ✅ 이미 완료된 항목
1. Resilience4j 기반 차단 시나리오
   - CircuitBreaker 설정 완료 (testCircuit)
   - OPEN / HALF_OPEN / CLOSED 상태 전이 확인
2. 부하 테스트 시나리오 구현
   - CPU busy-loop 기반 부하 (/api/load/cpu)
   - DB READ 반복 부하 (/api/load/db-read)
   - DB WRITE 반복 부하 (/api/load/db-write)
   - 모든 부하는 상한값 강제 적용 (테스트 안정성 확보)
3. Observability 기본 구성
   - 모든 실행 결과 spring_load_test_logs 저장
   - Spring Boot Actuator 적용
   - Micrometer + Prometheus 연동
   - /actuator/health, /actuator/prometheus 노출
   - API 호출 지연 및 부하 상황 메트릭 수집 가능
4. AOP 기반 로깅 구조
   - Service / Controller 직접 로그 제거
   - `LogAspect` 단일 진입점 로깅
   - LOAD_START / LOAD_END / LOAD_FAIL / LOAD_ERROR 이벤트 확립
   - 실행 시간(duration) 기반 로그 기록
   - trace_id 기반 요청 단위 로그 추적
5. 응답 포맷 및 예외 처리 표준화
   - `DefaultResponse<T>` 공통 응답 포맷
   - `ApiException` + `GlobalExceptionHandler`
   - HTTP 상태 코드와 비즈니스 에러 코드 분리
6. 환경 분리 전략
   - `application.yml` (공통)
   - `application-local.yml`
   - `application-docker.yml`
   - `SPRING_PROFILES_ACTIVE` 기반 실행
   - Gradle `bootRun` 기본값 local 설정
7. JSON Logback 적용
   - Wazuh 연계 전제
   - `logstash-logback-encoder` 적용
   - `trace_id / level / duration / event` 필드 구조화
   - 현재는 텍스트 로그 중심으로 테스트, JSON 로그는 병행 가능 상태
8. Vault 연동
   - Spring Cloud Vault Client 연동 구조 검증 완료
   - Vault KV(v2) 기반 PostgreSQL 자격 증명 동적 로딩
     - DB 자격 증명 하드코딩 완전 제거
   - 실제 Docker 서비스 실행 경로에서 Vault 연동 기동 확인
   - 운영 고도화(Secret rotation, Auth, HA)는 의도적으로 제외
9. 외부 부하 테스트 연계
    - 부하 유발 API 구현
    - JMeter 연계
    - 동시 사용자 증가에 따른 DB 커넥션 풀 고갈 시 CircuitBreaker OPEN 시점 분석
10. Prometheus 메트릭 확장
   - Custom Metrics
     - rate_limit_blocked_total
     - rate_limit_allowed_total
   - Built-in Metrics
     - resilience4j.circuitbreaker.state
     - hikaricp.connections.*
     - http.server.requests
11. Redis 캐싱 테스트
    - READ 시 캐싱 적용
    - Read Replica + Cache 비교 테스트
    - 캐시 무효화 전략은 TTL 기반 단순 전략만 사용
12. Graceful Shutdown 적용
    - SIGTERM 수신 시 신규 요청 수락을 중단하고(in-flight 요청은 마무리) 안전 종료
    - `server.shutdown=graceful` 설정 추가
    - spring.lifecycle.timeout-per-shutdown-phase로 종료 유예 시간 고정
    - 컨테이너 종료 유예(DevOps의 terminationGracePeriodSeconds) 보다 작거나 같아야 함
    
<br>

### ⏳ 아직 진행하지 않은 항목
1. `spring_logs` 테이블 실제 연동
   - 현재는 Logback / 콘솔 중심
   - DB 로그 저장은 구조(AOP, 정책, 도메인) 만 설계된 상태
   - spring_logs 테이블 및 도메인/Repository는 존재
   - 실제 저장 로직(LogAspect 연계)은 아직 미적용
   - 향후:
     - `LogAspect → SystemLog` 저장
     - 배치 기반 백업 후 truncate 전략 적용
2. DB 백업 및 로그 정리 시나리오
   - 로그 정리 주기
   - 백업 정책 테스트
3. Spring Security 도입
   - 프론트엔드 연동 전 필수 단계
   - 무제한 호출 / 오남용 방지
   - 테스트용 API 보호
   - 운영 환경을 가정한 최소 보안 레이어 적용
---