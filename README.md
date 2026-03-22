# 🚖 Trip Service (Core Domain)

> **여정의 생명주기(생성~종료)를 관리합니다.**

## 🛠 Tech Stack
| Category | Technology                           |
| :--- |:-------------------------------------|
| **Language** | **Java 17**                          |
| **Framework** | Spring Boot (WebFlux + MVC Hybrid)   |
| **Messaging** | Apache Kafka (Reactive Kafka, Spring Kafka)     |
| **Database** | MySQL (JPA), MySQL (JPA), Redis (Reactive/String, Pub/Sub) |
| **Others** | ShedLock (Scheduler 분산 락), WebSocket        |

## 📡 API Specification

| Method | URI | Description |
| :--- | :--- | :--- |
| `POST` | `/api/trips` | 배차 요청 및 여정 생성 |
| `GET` | `/api/trips/{id}` | 여정 상세 조회 |
| `PUT` | `/api/trips/{id}/arrive` | 기사 도착 처리 |
| `PUT` | `/api/trips/{id}/start` | 운행 시작 |
| `PUT` | `/api/trips/{id}/complete` | 운행 종료 |
| `PUT` | `/api/trips/{id}/cancel` | 여정 취소 |

## 🔄 Saga Pattern Flow (Distributed Transaction)

**MSA 환경에서의 데이터 정합성을 위해 이벤트 기반의 Choreography Saga 패턴을 적용했습니다.**

### 🟢 Happy Path (정상 흐름)
1.  **운행 종료 요청** (`PUT /complete`) 수신.
2.  **Trip Service:** DB 상태를 `PAYMENT_PENDING`로 변경하고 `TripCompletedEvent`를 Outbox에 저장.
3.  **Payment Service:** 이벤트 수신 후 결제 승인 시도 → 성공 시 `PaymentCompletedEvent` 발행.
4.  **Trip Service:** 결제 완료 이벤트 수신 후 최종 상태 `COMPLETED` 확정.

### 🔴 Failure Path (보상 트랜잭션)
1.  **Payment Service:** 결제 실패 시 `PaymentFailedEvent` 발행.
2.  **Trip Service:** 실패 이벤트 수신 후 **보상 트랜잭션** 실행.
    * 상태를 `PAYMENT_FAILED`로 롤백 및 완료 처리 취소.

## 🚀 Key Improvements
* **Hybrid Architecture:** 외부 API 호출 구간은 **WebFlux**로 논블로킹 처리로 스레드 효율을 높이, 트랜잭션 구간은 **Blocking(JPA)**으로 처리하여 성능과 안정성 동시 확보했습니다.
* **Transactional Outbox Pattern (`FOR UPDATE SKIP LOCKED`):** 이벤트 발행 실패로 인한 데이터 정합성 문제를 해결하기 위해 Outbox 테이블을 도입했습니다. Scheduler와 `SKIP LOCKED`를 조합하여 다중 인스턴스 환경에서도 DB 락 경합 없이 이벤트를 안전하게 폴링 및 발행합니다.
* **Dead Letter Topic (DLT) & Dashboarding:** `TripEventDltConsumer`를 통해 처리에 실패한 메시지를 DB(`FailedEvent`)에 적재하고, 관리자가 직접 재발행(Replay)하거나 폐기할 수 있는 어드민 API를 구축하여 장애 복구력(Resilience)을 높였습니다.
* **Reactive Kafka Micro-batching:** 기사의 고빈도 위치 업데이트를 처리하기 위해 `Reactive Kafka`를 도입했습니다. `bufferTimeout`을 활용한 마이크로 배치 처리(최대 500개 또는 100ms)로 Redis MultiGet 및 Pub/Sub 방송 효율을 극대화했습니다.
* **DB 동시성 제어 및 자동 복구:** 상태 변경 API에 비관적 락(`Pessimistic Lock`)을 적용하고, 락 타임아웃이나 데드락 발생 시 `@Retryable`을 통해 자동으로 재시도하도록 구성하여 트랜잭션 충돌을 방어했습니다.
* **WebSocket & Redis Pub/Sub Heartbeat:** 승객의 실시간 위치 추적을 위해 WebFlux 기반 WebSocket을 구현했습니다. Redis 채널을 구독하며, `PING/PONG` 하트비트와 `Timeout` 로직을 통해 좀비 커넥션을 방지합니다.


----------

## 아키텍쳐
<img width="2324" height="1686" alt="Image" src="https://github.com/user-attachments/assets/81a25ff9-ee02-4996-80d3-f9217c3b7750" />
