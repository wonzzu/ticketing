# TicketOn 기술 문서

TicketOn은 기능 목록보다 **동시 요청이 같은 자원을 변경할 때 무엇이 깨지는지**, 그리고 **Redis와 MySQL 사이의 부분 실패를 어떻게 복구하는지**에 초점을 둔 프로젝트다. 이 문서들은 완성된 구조만 소개하지 않고 문제 재현, 선택지, 구현, 테스트와 측정 결과까지 기록한다.

## 동시성·정합성

| 주제 | 해결한 문제 | 핵심 장치 |
|---|---|---|
| [대기열 원자성](queue-atomicity.md) | 진입과 승급이 겹칠 때 active 정원이 초과되는 문제 | Redis ZSet, Lua, Redisson |
| [좌석·예매 정합성](seat-consistency.md) | Redis 선점 성공 후 DB 저장이 실패하는 부분 실패 | `SET NX`, 소유자 검증 Lua, 트랜잭션 콜백 |
| [예매 멱등성과 결제 직렬화](idempotency-payment-lock.md) | 재요청으로 예매·결제가 중복 생성되는 문제 | 멱등키, 비관적 락, DB UNIQUE |

## 성능 개선

| 주제 | 변경 | 결과 |
|---|---|---:|
| [N+1 제거](n-plus-one.md) | QueryDSL fetch join + batch fetch | 예매 `62 → 5`, 좌석 `101 → 1 queries` |
| [공연 상세 캐시](cache-performance.md) | Redis Cache + 변경 시 무효화 | `616 → 1,660 TPS`, p95 `268 → 101ms` |
| [회원 검색 인덱스](member-index.md) | `(member_status, created_at)` 복합 인덱스 | SQL `877 → 33ms` |
| [성능 병목 종합](performance-bottleneck.md) | 인덱스 이후 남은 지연을 Pool 실험과 DTO Projection으로 추적 | 회원 검색 p95 `31.84s → 746ms` |
| [정산 배치 튜닝](batch-tuning.md) | chunk·fetchSize·Reader 실행계획 측정 | 근거 없는 튜닝을 원복하고 chunk 1,000 유지 |

## 다중 인스턴스·고가용성

| 주제 | 검증 결과 |
|---|---|
| [분산 스케줄러](distributed-scheduler.md) | 서버 3대에서 정산 실행 `3회 → 1회`, 5분간 오류 `20 → 0` |
| [Redis Sentinel](redis-sentinel.md) | 마스터 장애·재선출·부활 노드 복제본 편입과 Redisson 락 복제 검증 |
| [결제 취소 후속 처리 유실](payment-cancel-ha-loss.md) | 서버 복구 후에도 `AFTER_COMMIT` 정산 Dirty가 유실되는 상태 재현 |
| [기술 의사결정](architecture-decisions.md) | Redis ZSet·Lua·Redisson·Sentinel·MQ 미도입의 선택 근거 |

## 검증 원칙

- 동시성·멱등성·쿼리 수처럼 결과가 결정적인 항목은 통합 테스트로 회귀를 막는다.
- 응답시간과 실행계획은 k6, Grafana, Hibernate Statistics, `EXPLAIN ANALYZE`로 측정한다.
- 효과가 재현되지 않거나 더 느려진 변경은 적용하지 않는다.
- Redis는 빠른 임시 상태를, MySQL은 예매·결제의 최종 상태를 담당한다.

[프로젝트 README로 돌아가기](../../README.md)
