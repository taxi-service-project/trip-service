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

## 🚀 Key Improvements
* **Hybrid Architecture:** 외부 API 호출 구간은 **WebFlux**로 병렬 처리, 트랜잭션 구간은 **Blocking(JPA)**으로 처리하여 성능과 안정성 동시 확보.
* **Fault Tolerance:** Kafka `acks=all` 및 Consumer `RECORD`로 데이터 유실 원천 차단.
* **Isolation:** Redis 캐시 갱신 실패가 DB 트랜잭션을 롤백시키지 않도록 예외 격리.