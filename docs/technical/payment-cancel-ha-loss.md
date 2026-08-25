# 서버는 복구됐지만, 커밋된 결제 취소는 정산에 도착하지 않았다

## 실험 배경

TicketOn은 로컬 확장 실험 환경에서 Nginx 뒤에 Spring Boot 인스턴스 세 대를 실행하고, Redis Master 1대·Replica 2대·Sentinel 3대로 장애 전환을 검증했다. 기존 검증은 서버 또는 Redis 장애 이후 새 요청을 다시 처리할 수 있는지에 집중했다.

검증 범위를 진행 중이던 요청까지 넓혀, 정산 집계가 완료된 결제를 취소하는 도중 처리 인스턴스가 종료되면 해당 취소가 정산 재집계까지 전달되는지 확인했다.

## 기존 처리 구조

결제 취소 트랜잭션은 `Payment`와 `Reservation` 상태를 변경하고 `PaymentCanceledEvent`를 발행한다. 트랜잭션 커밋 이후 `@TransactionalEventListener(AFTER_COMMIT)`가 통계와 정산 Dirty를 별도 트랜잭션으로 등록한다.

```text
Payment·Reservation 취소 TX
          ↓ COMMIT
PaymentCanceledEvent(AFTER_COMMIT)
          ↓
SettlementDirtyDate 저장
          ↓
정산 재집계 Batch
```

이 구조에서 DB 커밋과 Dirty 등록은 하나의 원자적 작업이 아니다. 커밋 후 리스너가 끝나기 전에 프로세스가 종료되면 취소 사실은 DB에 남지만 후속 작업 의무는 메모리와 함께 사라질 수 있다.

## 장애 주입 방법

커밋과 리스너 실행 사이의 매우 짧은 구간을 재현 가능하게 넓히기 위해 운영 코드에 `sleep`을 추가하지 않았다. 대신 별도의 MySQL 트랜잭션에서 실험 대상과 동일한 `settlement_dirty_date` UNIQUE Key를 미커밋 상태로 INSERT했다.

1. 이미 정산된 `PAID` 결제와 정산금액을 기록했다.
2. 별도 DB 연결에서 동일 Dirty Key를 INSERT하고 커밋하지 않았다.
3. 취소 요청을 Spring1에 직접 전송했다.
4. 별도 연결에서 `Payment=CANCELED`와 `Reservation=CANCEL` 커밋을 확인했다.
5. 실제 `AFTER_COMMIT` 리스너의 Dirty INSERT가 UNIQUE Key 잠금에서 대기하는 것을 확인했다.
6. Spring1을 종료하고 잠금 주입 트랜잭션을 ROLLBACK했다.
7. Spring1을 재기동한 뒤 HTTP와 DB 상태를 다시 확인했다.

미커밋 행은 다른 트랜잭션에서 조회되지 않지만 동일 UNIQUE Key INSERT를 대기시킨다. Spring1 종료 후 잠금 트랜잭션도 ROLLBACK했으므로 실험용 Dirty 행은 최종 상태에 남지 않는다.

## 관측 결과

| 구분 | 취소 전 | Spring1 종료·복구 후 |
|---|---:|---:|
| Payment | `PAID` | `CANCELED` |
| Reservation | `CONFIRMED` | `CANCEL` |
| Settlement Dirty | 0건 | 0건 |
| 정산 총액 | 1,133,880,000원 | 1,133,880,000원 |
| Spring1 | `UP` | 재기동 후 `UP` |
| Nginx | `UP` | 재기동 후 HTTP 200 |

취소된 결제금액은 154,000원이었지만 기존 정산 총액은 변하지 않았다. Dirty도 없으므로 현재 재집계 배치는 이 날짜를 다시 처리할 이유를 발견할 수 없다.

## 확인된 원인

현재 애플리케이션 이벤트는 같은 JVM 안에서 실행된다. `AFTER_COMMIT`은 DB 커밋 이후 실행 시점만 지정할 뿐, 실행할 작업을 영속 저장하거나 다른 인스턴스가 이어받도록 보장하지 않는다.

Spring2와 Spring3이 살아 있어도 Spring1 메모리에 있던 이벤트를 알 수 없고, Spring1을 재기동해도 이미 사라진 이벤트는 재생되지 않는다. 따라서 애플리케이션 인스턴스 가용성과 커밋 이후 후속 작업의 전달 보장은 서로 다른 문제다.

## 후속 개선 방향

결제 취소와 발행할 이벤트를 같은 MySQL 트랜잭션에 저장하는 Transactional Outbox를 적용한다. Relay가 Outbox를 RabbitMQ로 발행하고, 정산·통계 Consumer가 기존 Dirty 등록을 수행하도록 분리한다.

```text
Payment·Reservation 취소 + Outbox 저장
              ↓ 동일 DB TX
            COMMIT
              ↓
        Outbox Relay → RabbitMQ
                         ├─ 정산 Dirty Consumer
                         └─ 통계 Dirty Consumer
```

이후 동일한 장애 시나리오를 다시 실행하여 서버 종료 시 Outbox가 남는지, 복구 후 이벤트가 전달되는지, 중복 전달에도 Dirty가 한 건만 유지되는지를 검증할 예정이다.

## 별도로 발견한 HA 설정 공백

Spring1 종료 직후 Spring2와 Spring3의 직접 health endpoint는 `UP`이었다. 그러나 현재 Nginx에는 짧은 upstream 연결 타임아웃과 `proxy_next_upstream` 정책이 없어 종료된 Spring1 연결을 즉시 우회하지 못했다. 이는 Outbox가 해결하는 이벤트 전달 문제와 분리하여 로드밸런서 fail-fast 정책으로 다뤄야 한다.

## 증거

- [취소 전 기준 상태](evidence/payment-cancel-ha-loss/baseline.txt)
- [장애 주입 과정](evidence/payment-cancel-ha-loss/failure.txt)
- [서버 복구 후 결과](evidence/payment-cancel-ha-loss/result.txt)
- [PaymentService](../../src/main/java/com/ticketing/payment/service/PaymentService.java)
- [SettlementDirtyEventListener](../../src/main/java/com/ticketing/settlement/batch/SettlementDirtyEventListener.java)
- [SettlementDirtyService](../../src/main/java/com/ticketing/settlement/service/SettlementDirtyService.java)

[기술 문서 목록](README.md)
