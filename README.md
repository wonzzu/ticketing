<h1 align="center">🎫 TicketOn</h1>

<p align="center">
  <img src="docs/ticketon-hero.png" alt="TicketOn 대표 이미지" width="100%" />
</p>

<p align="center">
  <b>티켓 오픈 순간의 트래픽을 제어하고 좌석 정합성을 보장하는 공연 예매 플랫폼</b>
</p>

<p align="center">
  <a href="https://ticketon.kro.kr"><b>서비스</b></a>
  ·
  <a href="https://ticketon.kro.kr/swagger-ui"><b>API 문서</b></a>
  ·
  <a href="docs/technical/README.md"><b>기술 문서</b></a>
  ·
  <a href="#기능-시연"><b>기능 시연</b></a>
</p>

---

## 핵심 성과

| 해결 영역 | 적용 기술 | 검증 결과 |
|---|---|---:|
| 동일 좌석 경합 | Redis `SET NX` + 소유자 검증 Lua | 동시 요청 **100건 중 1건 성공** |
| 대기열 정원 | Redis ZSet + 진입·승급 Lua | active **100명 이하 유지** |
| 내 예매 N+1 | QueryDSL fetch join + batch fetch | **62 → 5 queries** |
| 공연 상세 캐시 | Redis Cache + 무효화 | **616 → 1,660 TPS**, p95 **268 → 101ms** |
| 관리자 회원 검색 | `(member_status, created_at)` 인덱스 | SQL **877 → 33ms** |
| 다중 인스턴스 배치 | Redisson 분산락 | 실행 **3회 → 1회**, 오류 **20건 → 0건** |

---

## 프로젝트 소개

TicketOn은 공연 탐색부터 대기열, 좌석 선택, 예매, 결제, 취소와 정산까지 연결한 티켓팅 서비스입니다. 화면과 CRUD 구현에 머무르지 않고 **많은 요청이 같은 좌석·재고·배치 작업에 동시에 접근할 때 발생하는 경쟁 조건과 부분 실패**를 직접 재현하고 해결하는 데 초점을 두었습니다.

Redis는 대기 순서, 좌석 선점, 쿠폰 재고처럼 빠르게 변하고 경쟁이 집중되는 상태를 담당합니다. MySQL은 예매·결제·정산의 최종 상태와 변경 이력을 관리합니다. 서로 다른 저장소를 하나의 로컬 트랜잭션으로 묶을 수 없는 구간은 트랜잭션 완료 콜백, 소유자 검증 Lua와 TTL로 연결했습니다.

```text
공연 탐색 → 대기열 → 좌석 선점 → 예매 → 결제 → 취소·정산
```

### 설계 원칙

- 대기열을 통과한 사용자만 예매 구간으로 진입시켜 트래픽을 평탄화합니다.
- Redis의 단일 원자 명령으로 충분한 곳에는 별도 분산락을 사용하지 않습니다.
- 여러 Redis 명령의 확인·변경은 Lua로 묶어 check-then-act 경쟁을 제거합니다.
- DB UNIQUE와 JPA 락을 최종 방어선으로 두어 Redis 우회와 코드 회귀도 차단합니다.
- 효과가 재현되지 않거나 더 느려진 최적화는 적용하지 않습니다.

---

## 시스템 아키텍처

<p align="center">
  <img src="docs/Ticketon%20아키텍쳐.png" alt="TicketOn 시스템 아키텍처" width="90%" />
</p>

<details>
<summary><b>ERD 보기</b></summary>

<br>

![TicketOn ERD](<docs/ticketon erd.png>)

</details>

---

## 핵심 기술 문제

### 1. 대기열의 진입과 승급을 원자화

Redis ZSet에 대기 순번과 active 만료 시각을 저장합니다. 진입 과정의 중복 확인·정원 확인·순번 발급과 승급 과정의 만료 정리·빈자리 계산·사용자 이동을 각각 Lua 한 번으로 처리합니다. Redisson 분산락은 여러 인스턴스의 스케줄러 실행 주체를 하나로 제한하고, Lua는 Redis 상태 변경 자체의 원자성을 담당합니다.

[대기열 원자성 상세 문서](docs/technical/queue-atomicity.md)

### 2. Redis 선점과 DB 예매 사이의 부분 실패 복구

좌석은 `SET NX`와 7분 TTL로 임시 선점하고 결제 후 DB의 `RESERVED` 상태로 확정합니다. Redis 선점 후 예매 DB 트랜잭션이 롤백되면 `afterCompletion(ROLLED_BACK)`에서 보상 해제하고, 결제 성공 시에는 DB 커밋이 끝난 `afterCommit()`에서만 임시 선점을 제거합니다. 해제 Lua는 현재 값이 회원 ID와 일치할 때만 삭제합니다.

[좌석·예매 정합성 상세 문서](docs/technical/seat-consistency.md)

### 3. 예매 멱등성과 동일 예약의 동시 결제

예매 재요청은 `(memberId, idempotencyKey)` 범위로 기존 결과를 반환하고 DB UNIQUE로 중복 생성을 막습니다. 동일 예약에 대한 결제 요청은 Reservation 행을 `SELECT ... FOR UPDATE`로 직렬화하고 `payment.reservation_id` UNIQUE를 최종 방어선으로 유지합니다.

[예매 멱등성과 결제 락 상세 문서](docs/technical/idempotency-payment-lock.md)

### 4. 선착순 쿠폰의 원자 발급과 멱등 복구

발급자 중복 확인과 재고 차감을 하나의 Lua 스크립트로 묶었습니다. Redis 발급 후 DB 저장이 롤백되면 issued Set에서 회원이 실제로 제거된 경우에만 재고를 증가시키는 복구 Lua를 실행합니다.

[쿠폰 발급 정합성 상세 문서](docs/technical/coupon-consistency.md)

### 5. 측정 기반 성능 개선

Hibernate Statistics로 N+1 쿼리 수를 고정하고, k6로 부하 구간의 p95와 처리량을 비교했습니다. 회원 검색은 `EXPLAIN ANALYZE`로 풀스캔과 filesort를 확인한 뒤 복합 인덱스를 적용했습니다. 공연 상세 캐시는 단건 조회 속도보다 반복 요청의 DB 도달을 제거하는 목적으로 적용했습니다.

[N+1](docs/technical/n-plus-one.md) · [캐시](docs/technical/cache-performance.md) · [인덱스](docs/technical/member-index.md) · [성능 종합](docs/technical/performance-bottleneck.md)

### 6. 다중 인스턴스 배치와 Redis 장애 전환

Spring Boot 인스턴스가 늘어나면 `@Scheduled`도 인스턴스 수만큼 실행됩니다. Redisson `tryLock()`으로 정산과 통계 배치의 실행 주체를 제한하고, 배치 자체는 날짜 단위 재실행에 안전하도록 설계했습니다. Redis는 Master 1대, Replica 2대, Sentinel 3대로 구성해 Master 장애와 복구 흐름을 검증했습니다.

[분산 스케줄러](docs/technical/distributed-scheduler.md) · [Redis Sentinel](docs/technical/redis-sentinel.md) · [배치 튜닝](docs/technical/batch-tuning.md)

> 문제 재현, 대안 비교, 테스트와 측정 결과는 [전체 기술 문서](docs/technical/README.md)에 정리했습니다.

---

## 기술 스택

### Backend

![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white)
![Spring Batch](https://img.shields.io/badge/Spring_Batch-6DB33F?style=flat-square&logo=spring&logoColor=white)
![JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square&logo=spring&logoColor=white)
![QueryDSL](https://img.shields.io/badge/QueryDSL-0769AD?style=flat-square&logoColor=white)

### Data & Coordination

![MySQL](https://img.shields.io/badge/MySQL_8-4479A1?style=flat-square&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_7-DC382D?style=flat-square&logo=redis&logoColor=white)
![Redisson](https://img.shields.io/badge/Redisson-DC382D?style=flat-square&logo=redis&logoColor=white)

### Test & Observability

![JUnit5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=flat-square&logo=k6&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white)

### Infrastructure

![AWS](https://img.shields.io/badge/AWS_EC2_·_S3-FF9900?style=flat-square&logo=amazonwebservices&logoColor=white)
![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white)

### Frontend

![Vue.js](https://img.shields.io/badge/Vue.js_3-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white)
![Pinia](https://img.shields.io/badge/Pinia-FFD859?style=flat-square&logoColor=black)
![Axios](https://img.shields.io/badge/Axios-5A29E4?style=flat-square&logo=axios&logoColor=white)
![Bootstrap](https://img.shields.io/badge/Bootstrap_5.3-7952B3?style=flat-square&logo=bootstrap&logoColor=white)

---

## 주요 기능

| 사용자 | 기능 |
|---|---|
| 일반 회원 | 회원가입·로그인, 공연 검색, 대기열, 좌석 선택, 예매·결제·취소, 리뷰 |
| 판매자 | 공연 등록, 공연 회차·등급별 가격 등록, 정산 내역 조회 |
| 관리자 | 공연 승인·반려, 회원 정지·해제, 공연장 관리, 매출 통계 |
| 공통 시스템 | JWT 재발급, Rate Limiting, Redis 캐시, 변경 이력, 일별 배치 |

---

## 기능 시연

<details>
<summary><b>공통 — 회원가입·로그인·공연 탐색</b></summary>

### 회원가입

![회원가입](docs/시연영상.gif/회원가입.gif)

### 로그인

![로그인](docs/시연영상.gif/로그인.gif)

### 카테고리별 공연 탐색

![카테고리 작동 확인](docs/시연영상.gif/카테고리%20작동%20확인.gif)

</details>

<details>
<summary><b>일반 회원 — 예매·결제·마이페이지·리뷰</b></summary>

### 공연 예매와 결제

![공연 예매](docs/시연영상.gif/홈화면%20예매.gif)

### 예매 내역과 취소

![마이페이지 예매 정보](docs/시연영상.gif/마이페이지%20예매%20정보.gif)

### 리뷰 등록

![리뷰 등록](docs/시연영상.gif/리뷰%20등록.gif)

</details>

<details>
<summary><b>판매자 — 공연 회차 등록·정산 조회</b></summary>

### 공연 회차와 등급별 가격 등록

![판매자 공연 회차 등록](docs/시연영상.gif/판매자%20공연%20회차%20등록.gif)

### 판매자 정산 조회

![판매자 정산 화면](docs/시연영상.gif/판매자%20정산%20화면.gif)

</details>

<details>
<summary><b>관리자 — 공연 검수·회원·공연장·매출 관리</b></summary>

### 공연 승인

![관리자 공연 승인](docs/시연영상.gif/관리자%20공연%20승인.gif)

### 공연 반려

![관리자 공연 반려](docs/시연영상.gif/관리자%20공연%20반려.gif)

### 회원 목록과 상세 조회

![관리자 회원 관리](docs/시연영상.gif/관리자%20회원%20관리.gif)

### 회원 정지와 해제

![관리자 회원 정지 해제](docs/시연영상.gif/관리자%20회원%20정지%20해제.gif)

### 공연장 관리

![관리자 공연장 관리](docs/시연영상.gif/관리자%20공연장%20관리.gif)

### 일별 매출 통계

![관리자 일별 매출](docs/시연영상.gif/관리자%20일별%20매출%20화면%20.gif)

</details>

---

## 배포 및 데모 계정

- 서비스: [https://ticketon.kro.kr](https://ticketon.kro.kr)
- Swagger: [https://ticketon.kro.kr/swagger-ui](https://ticketon.kro.kr/swagger-ui)
- 공통 비밀번호: `test1234`

| 역할 | 계정 |
|---|---|
| 관리자 | `admin@test.com` |
| 판매자 | `seller1@test.com` |
| 일반 회원 | `normal@test.com` |

---

## 문서

- [전체 기술 문서](docs/technical/README.md)
- [기술 의사결정](docs/technical/architecture-decisions.md)
- [성능 병목 분석](docs/technical/performance-bottleneck.md)
- [Redis Sentinel 검증](docs/technical/redis-sentinel.md)
