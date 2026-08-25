# Payment Cancellation HA Loss Reproduction Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot 다중 인스턴스 환경에서 결제 취소 커밋 직후 처리 인스턴스가 종료될 때 정산 재집계 신호가 유실되는 현재 구조의 실패 상태를 재현하고 증거로 남긴다.

**Architecture:** Nginx 뒤의 Spring Boot 3대와 기존 Redis Sentinel 환경을 유지한다. 결제 취소는 특정 인스턴스로 전송하고, `AFTER_COMMIT` 정산 Dirty 등록 구간을 DB 잠금으로 지연시킨 뒤 해당 인스턴스를 종료하여 다른 인스턴스의 서비스 가용성과 정산 데이터 불일치를 각각 확인한다.

**Tech Stack:** Spring Boot 3.4, MySQL 8, Docker Compose, Nginx, Redis Sentinel, PowerShell

**Spec:** `docs/technical/payment-cancel-ha-loss.md`

## Global Constraints

- 실제 운영 장애라고 표현하지 않고 로컬 HA 장애 주입 실험으로 기록한다.
- 기존 결제·정산 운영 코드는 실험 단계에서 수정하지 않는다.
- 비밀번호와 JWT는 증거 파일 및 문서에 저장하지 않는다.
- Git commit, push, PR은 수행하지 않는다.

---

### Task 1: 기준 상태와 대상 데이터 확정

**Files:**
- Create: `docs/technical/evidence/payment-cancel-ha-loss/baseline.txt`

- [ ] Nginx와 Spring 3대의 `/actuator/health`가 모두 `UP`인지 확인한다.
- [ ] 정산 완료 상태이면서 취소 가능한 `PAID` 결제 한 건을 찾는다.
- [ ] Payment 상태, Dirty 존재 여부, 정산금액을 동일 시각 기준으로 기록한다.

### Task 2: AFTER_COMMIT 장애 구간 재현

**Files:**
- Create: `scripts/ha/payment-cancel-loss-reproduction.ps1`
- Create: `docs/technical/evidence/payment-cancel-ha-loss/failure.txt`

- [ ] 동일 Dirty UNIQUE 키에 대한 미커밋 INSERT로 실제 리스너의 INSERT를 대기시킨다.
- [ ] 취소 요청을 Spring1 `localhost:8081`로 직접 전송한다.
- [ ] Payment가 `CANCELED`로 커밋된 것을 별도 DB 연결에서 확인한다.
- [ ] Spring1 컨테이너를 종료한 뒤 잠금 트랜잭션을 ROLLBACK한다.

### Task 3: 서비스 가용성과 데이터 불일치 검증

**Files:**
- Create: `docs/technical/evidence/payment-cancel-ha-loss/result.txt`

- [ ] Nginx `localhost:8080`이 Spring2 또는 Spring3을 통해 계속 응답하는지 확인한다.
- [ ] Payment가 `CANCELED`인지 확인한다.
- [ ] Settlement Dirty가 0건인지 확인한다.
- [ ] 기존 Settlement 금액이 취소 전 금액으로 남아 있는지 확인한다.

### Task 4: 기술 문서 작성

**Files:**
- Create: `docs/technical/payment-cancel-ha-loss.md`

- [ ] 실험 배경을 기존 HA 검증의 범위 확장으로 설명한다.
- [ ] 장애 주입 방법과 타임라인을 재현 가능한 수준으로 기록한다.
- [ ] 관측 결과와 확인된 원인을 분리해 작성한다.
- [ ] 해결 전 상태이므로 Transactional Outbox는 후속 개선 방향으로만 명시한다.
