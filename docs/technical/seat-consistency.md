# Redis 선점 이후 DB 저장이 실패하면 좌석은 어떻게 복구할까?

## 문제 상황

예매 시작부터 결제까지 DB 좌석을 곧바로 `RESERVED`로 바꾸면 결제를 완료하지 않은 사용자 때문에 좌석이 장시간 잠길 수 있다. TicketOn은 Redis에 7분짜리 임시 선점을 만들고 결제 완료 시 DB 좌석을 최종 확정한다.

하지만 Redis 선점과 DB 예매 저장은 하나의 트랜잭션으로 묶을 수 없다. Redis 선점만 성공하고 DB 트랜잭션이 실패하면 실제 예매는 없는데 좌석만 점유되는 부분 실패가 발생한다.

## 지켜야 할 조건

- 같은 좌석은 동시에 한 회원만 선점한다.
- 여러 좌석 중 하나라도 실패하면 먼저 선점한 좌석도 돌려놓는다.
- 예매 트랜잭션이 롤백되면 Redis 선점도 보상 해제한다.
- 다른 회원이 소유한 선점은 해제하지 않는다.
- 결제 시점에도 선점 소유자가 결제 회원인지 다시 확인한다.
- DB 좌석 확정 후에만 임시 선점을 제거한다.

## 검토한 방법

| 방법 | 장점 | 판단 |
|---|---|---|
| DB 상태만 사용 | 최종 데이터와 한 트랜잭션에서 처리 가능 | 결제 전 임시 점유와 TTL 표현이 어렵다. |
| 좌석별 분산락 | 임계 구역 전체를 보호할 수 있다. | 좌석 단위 선점에는 원자적 `SET NX`가 더 직접적이다. |
| Redis `SET NX` | 좌석별 원자 선점과 TTL을 함께 제공한다. | DB 실패를 자동으로 되돌릴 수 없어 보상 처리가 필요하다. |

TicketOn은 **Redis는 임시 선점, MySQL은 최종 예매 상태**를 담당하도록 역할을 나눴다.

## 구현

1. `SET NX`로 좌석별 키를 생성하고 값에 회원 ID를 저장한다.
2. 여러 좌석 선점 중 하나라도 실패하면 이미 얻은 좌석을 즉시 해제한다.
3. 해제 Lua 스크립트는 저장된 회원 ID가 요청자와 같은 경우에만 키를 삭제한다.
4. 예매 트랜잭션이 롤백되면 트랜잭션 완료 콜백에서 Redis 선점을 보상 해제한다.
5. 결제 단계에서 예약 잠금과 선점 소유권을 다시 확인한 뒤 DB 좌석을 확정한다.
6. 결제 커밋 이후 임시 선점을 해제한다.

```text
좌석 선택
  → Redis SET NX 임시 선점
  → 예매 DB 저장
      ├─ rollback: 소유권 확인 후 Redis 보상 해제
      └─ commit: 결제 대기
  → 결제 시 소유권 재검증
  → DB 좌석 RESERVED
  → commit 이후 Redis 선점 해제
```

## 검증

- 서로 다른 100명이 동일 좌석에 동시에 요청: **1명 성공, 99명 실패**
- 다른 회원이 가진 좌석 해제 요청: 소유권이 다르면 선점 유지
- Redis 선점 후 예매 저장 실패: DB 롤백 및 Redis 선점 보상 해제
- 동일 예약 동시 결제: 결제 데이터 1건만 생성

## 남은 한계

- Redis와 MySQL을 하나의 원자적 트랜잭션으로 만들지는 않았다. 애플리케이션이 보상 콜백 실행 전에 강제 종료되는 구간은 만료·복구 전략에 의존한다.
- Redis 장애 중에는 신규 선점을 허용하지 않는 fail-closed 정책이다. 예매 가용성보다 중복 예매 방지를 우선했다.
- 7분 TTL은 현재 결제 흐름에 맞춘 정책값이다. 실제 결제 이탈률과 결제 소요 시간에 따라 조정해야 한다.

## 관련 코드

- [SeatHoldService](../../src/main/java/com/ticketing/event/service/SeatHoldService.java)
- [ReservationService](../../src/main/java/com/ticketing/reservation/service/ReservationService.java)
- [PaymentService](../../src/main/java/com/ticketing/payment/service/PaymentService.java)
- [좌석 선점 동시성 테스트](../../src/test/java/com/ticketing/event/service/SeatHoldServiceTest.java)
- [예매 롤백 보상 테스트](../../src/test/java/com/ticketing/reservation/ReservationSeatHoldCompensationTest.java)
- [동일 예약 동시 결제 테스트](../../src/test/java/com/ticketing/payment/service/PaymentConcurrencyTest.java)

