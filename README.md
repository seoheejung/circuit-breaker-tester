# Load & Observability Test Backend

> Spring Boot 기반 단일 API 서비스에서   
> 의도적 부하, 서킷 브레이커, 관측(Observability)을 테스트하기 위한 백엔드 프로젝트

---

## 1. 프로젝트 목적
> 본 프로젝트는 단순 CRUD API 구현이 아닌,    
> 시스템의 **임계점(Breaking Point)**을 탐색하고 방어 기제를 검증하는 것을 목적으로 한다.

1. 의도적 부하 생성: CPU, DB 등 주요 자원에 부하를 가해 시스템 지연 유도
2. 방어 기제 검증: Resilience4j 서킷 브레이커와 Rate Limit의 트리거 조건 및 복구 프로세스 실증
3. 관측성(Observability) 확보: Prometheus 및 Snapshot API를 통해 장애 상황을 데이터로 시각화
4. 인프라 한계 체험: Docker 단일 노드 환경에서 자원 제약에 따른 시스템 거동 분석

⚠️ 주의: 성능 최적화가 아닌, **“시스템이 망가지기 직전 어떤 일이 벌어지는지”**를 관측하기 위한 테스트 전용 백엔드

---

## 2. 기술 스택
| 구분          | 기술                              |
| ------------- | -------------------------------- |
| Language      | Java 17                          |
| Framework     | Spring Boot **3.4.2**            |
| Build         | Gradle                           |
| DB            | PostgreSQL 16                    |
| Cache         | Redis 7                          |
| Resilience    | Resilience4j                     |
| Observability | Actuator, Micrometer, Prometheus |
| Infra         | Docker (Single-node 환경 기준)    |

---

## 3. 디렉토리 구조
```
services/service-a/backend/
├── src/main/java/com/exit8/
│   ├── controller/      # API 진입점
│   ├── service/         # 부하 생성 / 상태 계산 / 차단 제어
│   ├── repository/      # DB 접근 계층
│   ├── domain/          # 부하 / 로그 도메인
│   ├── dto/             # 공통 응답 포맷 및 상태 모델
│   ├── filter/          # TraceId / RateLimit
│   ├── logging/         # AOP 기반 관측 로깅
│   ├── observability/   # 이벤트 버퍼 / 메트릭 보조
│   ├── state/           # 런타임 Feature 상태 저장소
│   └── config/          # DB / Redis / Metrics 설정
│
└── src/main/resources/
    ├── application.yml
    ├── application-local.yml
    ├── application-docker.yml
    └── logback-spring.xml

```

---

## 4. 주요 기능

### 부하 시나리오 (Fault Injection)
- CPU Load: Busy-loop 연산을 통한 실제 CPU 점유율 상승 유도
- DB Read/Write: SELECT/INSERT 반복 수행을 통한 커넥션 풀 고갈 및 지연 재현
- 캐시 전략 테스트: Redis 캐시 적용 여부에 따른 DB 부하 변동 관측

### 2단계 방어 체계 (Defense Mechanism)
- 1차 방어: IP 기반 Rate Limit (반복적 트래픽 차단)
- 2차 보호: Resilience4j CircuitBreaker (장애 전파 방지 및 서비스 격리)

### 실시간 관측 (Observability)
- Trace Tracking: 모든 요청에 고유 trace_id를 부여하여 전구간 흐름 추적
- Snapshot API: 현재 시스템 상태(서킷 상태, 풀 가용량 등)의 즉각적인 스냅샷 제공
- Metric Exposure: Micrometer 기반 메트릭을 Prometheus 포맷으로 노출

---

## 5. 프로젝트 실행 방법 (Quick Start)

###  로컬 실행 (Gradle)
```
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### Docker 실행
```
docker run -p 8080:8080 \
  --name service-a-backend \
  --env-file .env \
  --network db \
  exit8/service-a-backend:test
```
> Vault 및 Docker 네트워크 구성은 `docs/setup.md` 참고

### 헬스 체크 
```
curl http://localhost:8080/actuator/health
```
---

## 6. 테스트 불변식
> 정밀한 계측을 위해 아래 설정값은 실험 도중 임의로 변경하지 않는다.

- CircuitBreaker: slidingWindowSize: 30, failureRateThreshold: 50% (15건 실패 시 OPEN)
- HikariCP: maximumPoolSize: 50, connectionTimeout: 5s
- Redis: TTL은 5분으로 고정하여 자연 만료 상황 통제
- Resource: Docker CPU/Memory 제한값을 테스트 시나리오별로 고정

---

## 7. 완료 현황 / 향후 확장 정리

### ✅ 완료된 항목
1. 트래픽 제어 및 장애 재현
   - Resilience4j CircuitBreaker 적용
     - CLOSED / HALF_OPEN / OPEN 상태 전이 확인
     - 고정 실험 설정(testCircuit)
   - IP 기반 Rate Limit 1차 방어
   - CPU / DB READ / DB WRITE 부하 API 구현
2. Observability & 계측
   - Spring Boot Actuator 적용
   - Micrometer + Prometheus 연동
   - `/actuator/health`, `/actuator/prometheus` 노출
   - Custom Metrics 추가
     - `rate_limit_blocked_total`
     - `rate_limit_allowed_total`
   - Built-in Metrics 활용
     - `resilience4j.circuitbreaker.*`
     - `hikaricp.connections.*`
     - `http.server.requests`
   - 모든 부하 실행 결과 spring_load_test_logs 저장
   - SystemSnapshot / RecentRequests API 제공
3. 로깅 아키텍처
   - AOP 기반 단일 진입점 로깅 (LogAspect)
   - LOAD_START / END / FAIL / ERROR 이벤트 체계 확립
   - **trace_id** 기반 요청 단위 추적
   - JSON Logback 구조화 로그 적용
     - `trace_id / level / duration / event`
     - Wazuh 연계 전제
4. 실행 환경 및 보안 기반
   - Profile 분리 전략 (local / docker)
   - Vault KV(v2) 기반 DB 자격 증명 외부화
     - username / password 하드코딩 제거
   - Docker 기반 실행 및 Healthcheck 구성
   - Graceful Shutdown 적용
     - SIGTERM 수신 시 안전 종료
5. 실험 재현성 보장
   - CircuitBreaker 설정 고정
   - Redis TTL 고정 (5분)
   - Docker 자원 조건 고정
   - JMeter 기반 외부 부하 테스트 연계
    
<br>

### ⏳ 향후 진행 예정
1. DB 로그 영속화 완성
   - spring_logs 테이블 실제 저장 로직 연결
     - LogAspect → SystemLog 저장
   - 배치 기반 로그 백업 및 정리 전략 수립

2. 보안 계층 확장
   - Spring Security 도입
     - 테스트 API 보호
     - 오남용 방지
     - 최소 인증/인가 레이어 설계
---

## 📚 상세 문서
 
### 실행 환경 구성 및 인프라 연동
- [docs/setup.md](docs/setup.md)

### 시스템 아키텍처 및 설계 원칙
- [docs/architecture.md](docs/architecture.md)

### API 및 관측 계약  
- [docs/api-and-observability.md](docs/api-and-observability.md)
  