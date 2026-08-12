# 예매 멱등성과 동일 예약의 동시 결제

## 예매 재요청

네트워크 재시도와 더블클릭으로 같은 요청이 반복돼도 예매는 한 건만 생성되어야 한다. 멱등성 범위를 `(memberId, idempotencyKey)`로 잡아 다른 회원이 우연히 같은 키를 사용해도 충돌하지 않게 했다.

- 애플리케이션: 기존 예매 조회 후 동일 결과 반환
- 동시 요청: 멱등성 소유 행을 비관적 락으로 직렬화
- 최종 방어: `(member_id, idempotency_key)` DB UNIQUE

## 동일 예약의 동시 결제

`existsByReservationId()`만으로는 확인과 저장 사이에 다른 요청이 들어올 수 있다. 결제의 기준이 되는 Reservation 행을 `PESSIMISTIC_WRITE`로 조회해 같은 예약에 대한 결제만 직렬화했다.

```text
요청 A: SELECT reservation ... FOR UPDATE → 결제 저장 → commit
요청 B: 같은 행에서 대기 → 락 획득 → 기존 결제 확인 → 중복 결제 예외
```

`payment.reservation_id` UNIQUE는 우회 경로와 코드 회귀를 막는 최종 방어선으로 유지한다.

## 검증

- 동일 회원·동일 멱등성 키 동시 예매 요청: 예매 1건
- 같은 키를 사용하는 서로 다른 회원: 각각 자신의 예매 생성
- 동일 예약 동시 결제: 결제 1건, 예매 상태 한 번만 확정

[기술 문서 목록](README.md)
