# 🚖 Trip Service (Core Domain)

> **여정의 생명주기(생성~종료)를 관리합니다.**

## 🛠 Tech Stack
| Category | Technology                           |
| :--- |:-------------------------------------|
| **Language** | **Java 17**                          |
| **Framework** | Spring Boot (WebFlux + MVC Hybrid)   |
| **Messaging** | Apache Kafka (Producer/Consumer)     |
| **Database** | MySQL (JPA), Redis (Reactive/String) |

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
2.  **Trip Service:** DB 상태를 `PAYMENT_PENDING`로 변경하고 `TripCompletedEvent` 발행.
3.  **Payment Service:** 이벤트 수신 후 결제 승인 시도 → 성공 시 `PaymentCompletedEvent` 발행.
4.  **Trip Service:** 결제 완료 이벤트 수신 후 최종 상태 `COMPLETED` 확정.

### 🔴 Failure Path (보상 트랜잭션)
1.  **Payment Service:** 결제 실패 시 `PaymentFailedEvent` 발행.
2.  **Trip Service:** 실패 이벤트 수신 후 **보상 트랜잭션** 실행.
    * 상태를 `PAYMENT_FAILED`로 롤백.
    * 사용자에게 "결제 실패 알림" 발송.

## 🚀 Key Improvements
* **Hybrid Architecture:** 외부 API 호출 구간은 **WebFlux**로 병렬 처리, 트랜잭션 구간은 **Blocking(JPA)**으로 처리하여 성능과 안정성 동시 확보.
* **Fault Tolerance:** Kafka `acks=all` 및 Consumer `RECORD`로 데이터 유실 원천 차단.


----------

## 아키텍쳐
<img width="2324" height="1686" alt="Image" src="https://github.com/user-attachments/assets/81a25ff9-ee02-4996-80d3-f9217c3b7750" />
