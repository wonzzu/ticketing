<h1 align="center">🎫 TicketOn</h1>

<p align="center">
  <img src="docs/ticketon-hero.png" alt="TicketOn 대표 이미지" width="100%" />
</p>

<p align="center">
  <b>티켓 오픈 순간의 트래픽을 제어하고 좌석 정합성을 보장하는 공연 예매 플랫폼</b>
</p>

<p align="center">
  <a href="https://ticketon.kro.kr">
    <img src="https://img.shields.io/badge/배포_서비스-바로가기-2ea44f?style=for-the-badge&logo=googlechrome&logoColor=white" alt="배포 서비스 바로가기" />
  </a>
  <a href="https://ticketon.kro.kr/swagger-ui">
    <img src="https://img.shields.io/badge/Swagger-API_DOCS-85ea2d?style=for-the-badge&logo=swagger&logoColor=black" alt="API 문서" />
  </a>
  <a href="docs/technical/README.md">
    <img src="https://img.shields.io/badge/Technical-DOCS-4f46e5?style=for-the-badge&logo=readthedocs&logoColor=white" alt="기술 문서" />
  </a>
</p>

<p align="center">
  <a href="#기능-시연"><b>🎬 기능 시연 보기</b></a>
</p>

---

## 핵심 성과

| 해결 영역 | 적용 기술 | 검증 결과 |
|---|---|---:|
| 동일 좌석 경합 | Redis `SET NX` + 소유자 검증 Lua | 동시 요청 **100건 중 1건 성공** |
| 대기열 정원 | Redis ZSet + 진입·승급 Lua | 동시 실행에도 **설정 정원 초과 방지** |
| 내 예매 N+1 | QueryDSL fetch join + batch fetch | **62 → 5 queries** |
| 공연 상세 캐시 | Redis Cache + 무효화 | **616 → 1,660 TPS**, p95 **268 → 101ms** |
| 관리자 회원 검색 | 복합 인덱스 + DTO Projection | p95 **31.84s → 746ms** |
| 다중 인스턴스 배치 | Redisson 분산락 | 실행 **3회 → 1회**, 오류 **20건 → 0건** |

---

## 프로젝트 소개

TicketOn은 공연 탐색부터 대기열, 좌석 선택, 예매, 결제, 취소와 정산까지 연결한 공연 예매 서비스입니다. 기능 구현 자체보다 **동일 자원에 요청이 집중될 때 발생하는 경쟁 조건, 저장소 간 부분 실패, 부하 상황의 병목을 재현하고 검증하는 것**에 초점을 두었습니다.

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

이력서와 포트폴리오에서 소개한 성과를 **문제 재현 → 선택 근거 → 구현 → 테스트** 순서로 상세 문서에 정리했습니다.

| 주제 | 해결한 문제 | 핵심 설계 | 코드·검증 |
|---|---|---|---|
| 대기열 상태 전이 | 신규 진입과 승급이 같은 active 여석을 중복 계산 | ZSet의 확인·순번 발급·상태 변경을 Lua로 단일 실행 | [대기열 원자성](docs/technical/queue-atomicity.md) |
| 좌석 임시 소유권 | Redis 선점 후 DB 예매가 롤백되는 부분 실패 | `SET NX`·소유자 검증 Lua·트랜잭션 콜백·TTL | [좌석·예매 정합성](docs/technical/seat-consistency.md) |
| 예매·결제 중복 | 재요청과 동일 예약 동시 결제로 데이터가 중복 생성 | 멱등키·`SELECT FOR UPDATE`·DB UNIQUE | [예매 멱등성과 결제 락](docs/technical/idempotency-payment-lock.md) |
| API 성능 병목 | 쿼리를 개선한 뒤에도 남은 응답 지연 | Statistics·k6·Grafana·실행계획으로 병목을 단계별 분리 | [성능 병목 종합](docs/technical/performance-bottleneck.md) |
| 다중 인스턴스 운영 | 서버 수만큼 중복 실행되는 배치와 Redis 단일 장애점 | Redisson 실행 조정·멱등 배치·Redis Sentinel | [분산 스케줄러](docs/technical/distributed-scheduler.md) · [Sentinel](docs/technical/redis-sentinel.md) |

<details>
<summary><b>성능 개선 측정 근거 보기</b></summary>

### 회원 검색 실행계획

| 복합 인덱스 적용 전 | 복합 인덱스 적용 후 |
|---|---|
| ![회원 검색 인덱스 전](docs/performance/member-index-before.png) | ![회원 검색 인덱스 후](docs/performance/member-index-after.png) |

### 인덱스 적용 후 확인한 커넥션 대기

![HikariCP 평균 획득 대기시간](docs/performance/hikari-acquire-time.png)

### 공연 상세 캐시 적용 후 DB 접근 제거

| 캐시 적용 전 | 캐시 적용 후 |
|---|---|
| ![캐시 적용 전 HikariCP](docs/performance/event-cache-pool-before.png) | ![캐시 적용 후 HikariCP](docs/performance/event-cache-pool-after.png) |

</details>

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

## 문서

- [전체 기술 문서](docs/technical/README.md)
- [기술 의사결정](docs/technical/architecture-decisions.md)
- [성능 병목 분석](docs/technical/performance-bottleneck.md)
- [Redis Sentinel 검증](docs/technical/redis-sentinel.md)
