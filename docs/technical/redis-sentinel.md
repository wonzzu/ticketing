# Redis Sentinel 장애 전환 검증

## 구성

Redis Master 1대, Replica 2대, Sentinel 3대로 구성했다. 애플리케이션과 Redisson은 Sentinel을 통해 현재 Master를 찾는다.

## 검증 과정에서 발견한 문제

### 감시 대상 호스트명

Sentinel이 Docker 호스트명으로 Master를 감시하면 컨테이너가 죽는 순간 이름 해석에 실패해 장애 판정이 중단됐다. 감시 주소를 고정 IP로 바꿔 해결했다.

### Redisson 락 복제 검증

Replica가 한 대뿐이면 Master 승격 직후 동기화할 Replica가 0대가 되어 Redisson의 `checkLockSyncedSlaves` 검증이 실패했다. 검증을 끄는 대신 Replica를 두 대로 늘려 락 복제 확인을 유지했다.

## 페일오버 결과

6회 측정 중 4회는 약 3~5초에 복구됐고, 리더 선출 재시도가 발생한 2회는 20~60초가 걸렸다. 평균값 하나로 숨기지 않고 정상 선출과 재시도 경로를 나눠 기록했다.

```text
Master 장애
→ Sentinel quorum 장애 판정
→ Replica 승격
→ 애플리케이션과 Redisson이 새 Master 연결
→ 기존 Master 부활 시 Replica로 편입
```

Replica 2대와 락 복제 검증을 유지한 상태에서 페일오버 3회 동안 Redisson 락 예외는 발생하지 않았다.

## 추가 관찰

`restart: unless-stopped` 환경에서 사용자 명령인 `docker stop/kill`과 실제 프로세스 크래시는 동작이 달랐다. `redis-cli shutdown nosave`로 크래시를 재현했을 때 컨테이너가 자동 재시작됐고, 부활 노드는 이전 Master 역할을 고집하지 않고 Replica로 편입됐다.

## 트레이드오프

Sentinel은 샤딩이 아니라 가용성을 해결한다. 데이터 규모보다 장애 전환이 우선인 현재 구조에 맞아 Cluster 대신 선택했다. 리더 선출이 재시도되면 복구 시간이 길어질 수 있으므로 운영 환경에서는 장애 감지 시간과 호스트 자원, 클라이언트 재연결 지표를 함께 관찰해야 한다.

[기술 문서 목록](README.md)
