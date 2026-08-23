# 29만 행 정렬을 복합 인덱스로 제거했다

## 문제

관리자 회원 검색은 상태로 필터링하고 가입일 역순으로 정렬한다.

```sql
SELECT * FROM member
WHERE member_status = 'SUSPENDED'
ORDER BY created_at DESC
LIMIT 20;
```

인덱스 적용 전에는 296,246행을 확인하고 filesort를 수행했다. 조회 조건과 정렬 순서를 반영해 `(member_status, created_at)` 복합 인덱스를 적용했다.

## 실행계획

| Before | After |
|---|---|
| `type=ALL`, 296,246 rows, `Using filesort` | `type=ref`, `idx_member_status_created`, Backward index scan |
| ![회원 검색 인덱스 전](../performance/member-index-before.png) | ![회원 검색 인덱스 후](../performance/member-index-after.png) |

`EXPLAIN ANALYZE`에서 SQL 실행 시간은 `877ms → 33ms`로 줄었다.

## 부하 테스트

| 지표 | 적용 전 | 적용 후 |
|---|---:|---:|
| 100 VU p95 | 31.8s | **7s** |
| 처리량 | 3.6 TPS | **15.2 TPS** |
| 300 VU 실패율 | 71.2% | **0%** |

인덱스가 SQL 병목을 크게 줄였지만 전체 응답은 여전히 느렸다. SQL은 33ms인데 HikariCP 커넥션 획득에 평균 약 3초가 걸렸다.

![HikariCP 평균 획득 대기시간](../performance/hikari-acquire-time.png)

커넥션 풀을 10에서 30으로 늘려도 TPS는 거의 같고 p95가 악화되어 원복했다. 인덱스 개선과 전체 요청 최적화는 별개이며, 실행계획 하나만 보고 성능 문제가 끝났다고 판단하지 않았다.

인덱스 이후에도 남은 p95 7초의 원인 추적과 DTO Projection 적용 결과는 [성능 병목 종합 문서](performance-bottleneck.md)에 이어서 정리했다.

## 인덱스를 남발하지 않은 기준

- 실제 조회 조건과 정렬을 동시에 지원하는가
- 선택도가 충분한가
- 쓰기 비용보다 단일 쿼리 비용 감소가 큰가
- FK가 이미 만든 인덱스와 중복되지 않는가

[기술 문서 목록](README.md)
