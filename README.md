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

| 좌석 정합성 | 대용량 조회 | 다중 인스턴스 |
|---|---|---|
| 동일 좌석 동시 요청 **100건 중 1건 성공** | 회원 검색 300 VU 실패율 **71.2% → 0%** | 정산 배치 **3회 → 1회** |
| [Redis·DB 부분 실패 보상](docs/technical/seat-consistency.md) | [SQL 실행 시간 877ms → 33ms](docs/technical/performance-bottleneck.md) | [5분간 오류 20건 → 0건](docs/technical/distributed-scheduler.md) |

> 회원 30만 명, 예매·결제 각 300만 건의 데이터에서 변경 전후를 비교했습니다. 단일 8GB 머신에서 측정한 상대 비교이며 운영 용량을 의미하지 않습니다.

---

## 프로젝트 소개

TicketOn은 티켓 오픈 순간 많은 사용자가 동시에 접속하는 상황을 가정한 공연 예매 플랫폼입니다. 기능 구현 자체보다 **대기열·좌석 선점·예매·결제·정산으로 이어지는 흐름에서 발생하는 동시성, 부분 실패와 데이터 정합성 문제를 재현하고 해결하는 것**에 초점을 두었습니다.

Redis는 대기 순서와 임시 좌석 선점처럼 빠르게 변하고 만료가 필요한 상태를 담당하고, MySQL은 예매·결제·정산의 최종 상태를 관리합니다. 두 저장소를 하나의 트랜잭션으로 묶을 수 없는 구간은 소유권 검증과 보상 처리로 연결했습니다.

또한 단일 서버에서 정상인 기능을 Spring Boot 3대 환경에서 다시 실행해 대기열 승급 경쟁과 정산 스케줄러 중복 실행을 확인했습니다. 성능 개선은 회원 30만 명, 예매·결제 각 300만 건의 데이터에서 같은 조건으로 측정하고, 효과가 없거나 성능을 악화시킨 변경은 원복했습니다.

```text
대기열 → 좌석 선점 → 예매 → 결제 → 정산
```

<details>
<summary><b>설계 목표와 검증 범위 보기</b></summary>

<br>

- 순간 트래픽이 예매 API와 DB로 한꺼번에 전달되지 않도록 진입 인원을 제한합니다.
- 동일 좌석과 한정 쿠폰에 대한 경쟁 요청은 하나만 성공하도록 원자적으로 처리합니다.
- Redis 작업 이후 DB 트랜잭션이 실패해도 임시 상태가 남지 않도록 보상합니다.
- 동일 요청의 재전송과 동시 결제에도 예매·결제 데이터가 중복 생성되지 않도록 멱등성을 검증합니다.
- 서버가 여러 대여도 대기열 정원을 넘지 않고, 정산 배치가 중복 실행되지 않도록 합니다.
- 부하 테스트 수치는 단일 8GB 머신에서 측정한 상대 비교로만 사용하며 운영 용량으로 과장하지 않습니다.

</details>

---

## 시스템 아키텍처

<p align="center">
  <img src="docs/Ticketon%20아키텍쳐.png" alt="TicketOn 시스템 아키텍처" width="90%" />
</p>

> 공개 서비스는 단일 VM으로 운영합니다. Spring Boot 3대와 Redis Sentinel 구성은 별도의 확장·장애 실험 환경에서 검증했습니다.

<details>
<summary><b>ERD 보기</b></summary>

<br>

![TicketOn ERD](<docs/ticketon erd.png>)

</details>

---

## 기술 문제 탐색

| 문제 | 핵심 질문 | 상세 문서 |
|---|---|---|
| 좌석 정합성 | Redis 선점 이후 DB 저장이 실패하면 좌석은 어떻게 복구할까? | [좌석 선점과 보상 처리](docs/technical/seat-consistency.md) |
| 대기열 원자성 | 여러 서버가 동시에 입장시켜도 정원을 넘지 않게 하려면? | [다중 인스턴스 대기열](docs/technical/queue-atomicity.md) |
| 정산 배치 | 서버를 세 대로 늘리자 정산이 세 번 실행된 이유는? | [분산 스케줄러](docs/technical/distributed-scheduler.md) |
| 성능 병목 | 쿼리를 줄였는데 왜 전체 응답은 여전히 느렸을까? | [측정 기반 성능 개선](docs/technical/performance-bottleneck.md) |

[전체 기술 문서 보기](docs/technical/README.md)

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

---

## 기능 시연

<details>
<summary><b>일반 회원 — 공연 예매·결제·취소</b></summary>

<br>

<p align="center">
  <img src="docs/시연영상.gif/홈화면%20예매.gif" alt="공연 예매" width="90%" />
</p>

<p align="center">
  <img src="docs/시연영상.gif/마이페이지%20예매%20정보.gif" alt="예매 조회와 취소" width="90%" />
</p>

</details>

<details>
<summary><b>판매자 — 공연 회차 등록·정산 조회</b></summary>

<br>

<p align="center">
  <img src="docs/시연영상.gif/판매자%20공연%20회차%20등록.gif" alt="공연 회차 등록" width="90%" />
</p>

<p align="center">
  <img src="docs/시연영상.gif/판매자%20정산%20화면.gif" alt="판매자 정산 조회" width="90%" />
</p>

</details>

<details>
<summary><b>관리자 — 공연 검수·회원 관리·매출 통계</b></summary>

<br>

<p align="center">
  <img src="docs/시연영상.gif/관리자%20공연%20승인.gif" alt="공연 승인" width="90%" />
</p>

<p align="center">
  <img src="docs/시연영상.gif/관리자%20회원%20관리.gif" alt="회원 관리" width="90%" />
</p>

<p align="center">
  <img src="docs/시연영상.gif/관리자%20일별%20매출%20화면%20.gif" alt="일별 매출 통계" width="90%" />
</p>

</details>
