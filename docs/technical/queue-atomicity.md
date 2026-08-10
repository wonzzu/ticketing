# 여러 서버가 동시에 입장시켜도 대기열 정원을 넘지 않게 하려면?

## 문제 상황

티켓 오픈 순간의 요청을 모두 예매 API로 보내면 DB와 커넥션 풀이 먼저 포화될 수 있다. TicketOn은 Redis 대기열로 예매 구간에 진입할 사용자를 제한한다.

단순히 Redis ZSet에 순번을 저장하는 것만으로는 충분하지 않다. 여러 서버가 동시에 `현재 인원 확인 → 빈자리 계산 → 대기자 이동`을 수행하면 같은 빈자리를 각자 확인해 활성 사용자가 정원을 초과할 수 있다.

## 지켜야 할 조건

- 먼저 들어온 사용자를 먼저 승급한다.
- 동일 회원에게 순번을 중복 발급하지 않는다.
- 활성 사용자는 정원 100명을 넘지 않는다.
- 만료된 활성 사용자를 제거하고 빈자리를 다시 사용한다.
- 여러 인스턴스가 동시에 진입·승급을 실행해도 결과가 같아야 한다.

## 원인

다음 과정은 각 Redis 명령이 안전해도 전체 과정은 안전하지 않다.

```text
active 인원 조회
→ 남은 자리 계산
→ waiting 사용자 조회
→ waiting에서 제거
→ active에 추가
```

두 인스턴스가 같은 상태를 읽으면 둘 다 동일 사용자를 승급하거나 같은 빈자리를 사용할 수 있다. 전형적인 **check-then-act 경쟁 조건**이다.

## 선택

- 대기 순서는 ZSet score로 관리한다.
- 증가 시퀀스로 같은 시각의 요청에도 순서를 부여한다.
- 진입과 승급의 확인·변경을 각각 Lua 스크립트 하나로 묶는다.
- 승급 스케줄러에는 Redisson 분산락을 추가해 불필요한 중복 실행을 줄인다.

분산락은 스케줄러 실행 주체를 제한하고, Lua는 Redis 안의 상태 변경 자체를 원자적으로 만든다. 둘의 책임을 분리했다.

## 구현 흐름

```text
대기열 진입 Lua
  → 만료 active 제거
  → active/waiting 중복 확인
  → 대기자가 없고 자리가 있으면 즉시 active
  → 아니면 sequence 증가 후 waiting ZSet 등록

승급 Lua
  → 만료 active 제거
  → 남은 자리 계산
  → waiting 선두 N명 조회
  → waiting 제거와 active 추가를 한 번에 수행
```

활성 사용자의 TTL은 10분이며 스케줄러는 3초마다 승급을 시도한다. 예매 생성이 커밋되면 해당 사용자를 활성 집합에서 제거해 다음 사용자가 들어올 자리를 만든다.

## 검증

- 동일 회원의 동시 진입 요청 100건: 대기 순번 1회만 발급
- 진입과 승급이 경쟁하는 상황을 반복해도 정원 불변식 유지
- 두 서버가 동시에 승급해도 활성 사용자 수가 100명을 넘지 않음
- 예매 완료 후 active 제거, TTL 만료 후 다음 사용자 승급 확인

## 남은 한계

- 폴링 방식이므로 대기자가 많아지면 상태 조회 요청도 증가한다. 규모가 더 커지면 폴링 간격 조절이나 SSE를 검토할 수 있다.
- 한 ZSet에 대기자가 집중되는 구조이므로 이벤트별 키 분리와 Redis 메모리 용량 계획이 필요하다.
- 공정성은 Redis에 진입한 순서를 기준으로 한다. 네트워크에서 먼저 출발한 요청의 절대적 순서까지 보장하지 않는다.

## 관련 코드

- [QueueService](../../src/main/java/com/ticketing/queue/service/QueueService.java)
- [동일 회원 진입 멱등성 테스트](../../src/test/java/com/ticketing/queue/service/QueueEntryIdempotencyTest.java)
- [진입·승급 경쟁 테스트](../../src/test/java/com/ticketing/queue/service/QueueEntryAdmissionRaceTest.java)
- [다중 인스턴스 승급 테스트](../../src/test/java/com/ticketing/queue/service/QueueMultiInstanceAdmissionTest.java)
- [대기열 생명주기 테스트](../../src/test/java/com/ticketing/queue/service/QueueLifeCycleTest.java)

