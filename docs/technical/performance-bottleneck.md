# 쿼리를 줄였는데 왜 전체 응답은 여전히 느렸을까?

## 측정 원칙

성능 개선은 기능 구현 후 감으로 적용하지 않고 다음 순서로 진행했다.

```text
동일한 데이터와 부하 조건 준비
→ Hibernate Statistics / k6 / Grafana로 측정
→ 병목 가설 수립
→ 한 가지 변경
→ 같은 조건으로 재측정
→ 다음 병목 확인
```

테스트 데이터는 회원 30만 명, 예매 300만 건, 결제 300만 건이다. k6 부하는 3분 동안 실행했다. 모든 구성요소가 단일 8GB 머신에 있어 결과는 절대 성능이 아니라 **같은 환경의 변경 전후 비교**로 해석했다.

## 1. 내 예매 목록의 N+1

예매 20건 조회에서 연관 엔티티 접근으로 62개의 쿼리가 발생했다. QueryDSL fetch join과 batch fetch를 적용해 쿼리를 5개로 줄였으며 Hibernate Statistics 테스트로 상한을 고정했다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 쿼리 수 | 62 | **5** |
| k6 p95 | 2.02s | **797ms** |
| 처리량 | 83 TPS | **167 TPS** |

## 2. 회원 상태 검색 풀스캔

관리자 회원 검색은 `member_status`로 필터링하고 `created_at`으로 정렬한다. 30만 행에서 `EXPLAIN ANALYZE`로 풀스캔을 확인하고 검색과 정렬 순서를 반영한 복합 인덱스 `(member_status, created_at)`를 적용했다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| SQL 실행 시간 | 877ms | **33ms** |
| 300 VU 실패율 | 71.2% | **0%** |
| p95 | 31.8s | **7s** |
| 처리량 | 3.6 TPS | **15.2 TPS** |

실패율은 제거됐지만 p95 7초는 여전히 길다. SQL 하나의 개선을 전체 요청 성능 해결로 과장하지 않았다.

## 3. 병목은 커넥션과 요청 대기로 이동했다

쿼리를 줄인 뒤 Grafana에서 Hikari pending이 최대 90까지 상승했다. 커넥션 풀 부족을 가정해 풀 크기를 10에서 30으로 늘렸지만 p95가 오히려 악화되어 원복했다.

SQL 실행은 약 33ms인데 커넥션 획득에 최대 약 3초가 걸렸고, 남은 시간은 Tomcat 스레드풀 큐잉 등의 가능성이 있다. 측정하지 못한 부분은 확정된 원인처럼 작성하지 않고 추정으로 남겼다.

## 4. 캐시는 빠른 PK 조회보다 부하 흡수를 위해 적용했다

공연 상세는 낮은 부하에서는 캐시 없이도 빠른 PK 조회였다. 캐시의 목적을 단건 응답시간 단축이 아니라 반복 조회가 DB로 전달되지 않도록 하는 것으로 정했다.

| 항목 | 캐시 전 | 캐시 후 |
|---|---:|---:|
| p95 | 268ms | **101ms** |
| 처리량 | 616 TPS | **1,660 TPS** |
| 반복 요청 DB 접근 | 발생 | **0** |

## 실패한 시도에서 얻은 판단

- 커넥션 풀을 크게 만들면 항상 빨라지는 것이 아니었다. DB가 처리할 동시 작업과 대기열이 함께 증가할 수 있어 측정 후 원복했다.
- MySQL 커서 옵션 없이 설정한 fetchSize는 기대한 방식으로 동작하지 않았다. `useCursorFetch` 적용 후에는 해당 환경에서 오히려 약 20% 느려졌다.
- 정산 Batch는 89,424건에서 chunk 100이 약 45% 느렸고 500 이상에서는 차이가 작았다. 가장 빠른 한 번보다 변동성과 메모리 여유를 고려해 1,000을 선택했다.

## 남은 한계

- 부하 생성기, 애플리케이션, MySQL, Redis가 같은 머신의 자원을 공유했다. 운영 용량 산정 자료로 사용할 수 없다.
- 300 VU에서 실패율은 제거했지만 p95 7초가 남았다. 서버 스레드·커넥션 대기와 DB 자원 사용을 분리한 후속 실험이 필요하다.
- 캐시는 읽기 부하를 줄이는 대신 무효화 정책과 Redis 장애 시 동작을 함께 관리해야 한다.

## 관련 코드와 테스트

- [ReservationRepositoryImpl](../../src/main/java/com/ticketing/reservation/repository/ReservationRepositoryImpl.java)
- [MemberRepositoryImpl](../../src/main/java/com/ticketing/member/repository/MemberRepositoryImpl.java)
- [EventService](../../src/main/java/com/ticketing/event/service/EventService.java)
- [예매 목록 쿼리 수 테스트](../../src/test/java/com/ticketing/reservation/ReservationQueryCountTest.java)
- [공연 좌석 쿼리 수 테스트](../../src/test/java/com/ticketing/event/service/EventSeatQueryCountTest.java)
- [공연 캐시 테스트](../../src/test/java/com/ticketing/event/service/EventCacheTest.java)
- [회원 검색 k6 스크립트](../../k6/member-search.js)
- [예매 목록 k6 스크립트](../../k6/findmine.js)

