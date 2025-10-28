# MSA 기반 Taxi 호출 플랫폼 - Trip Service

Taxi 호출 플랫폼의 **핵심 여정(Trip) 관리**를 담당하는 마이크로서비스입니다. Kafka로부터 배차 완료이벤트를 수신하여 여정을 생성하고, 기사 도착, 운행 시작/종료, 취소 등 여정의 전체 생애주기를 관리합니다. 또한, 결제 완료/실패 이벤트에 따라 요금 정보를 업데이트하거나 상태를 롤백하는 보상 로직을 포함합니다. WebSocket을 통해 승객에게 기사의 실시간 위치를 전달하는 기능도 제공합니다. 

## 주요 기능 및 워크플로우

1.  **여정 생성 (`TripEventConsumer` → `TripService`):**
    * `matching_events` 토픽에서 `TripMatchedEvent` 수신 시 새로운 `Trip` 엔티티 생성.
    * 출발지/도착지 좌표를 **Naver Maps Client**를 통해 주소로 변환하여 저장.
    * DB에 여정 정보 저장.
2.  **여정 상태 변경 (API Endpoints - `/api/trips/{tripId}`):**
    * `PUT /arrive`: 기사 도착 처리 및 `DriverArrivedEvent` Kafka 발행.
    * `PUT /start`: 운행 시작 처리.
    * `PUT /complete`: 운행 종료 처리 및 `TripCompletedEvent` Kafka 발행.
    * `PUT /cancel`: 여정 취소 처리 및 `TripCanceledEvent` Kafka 발행.
3.  **결제 결과 반영 (`TripEventConsumer` → `TripService`):**
    * `payment_events` 토픽에서 `PaymentCompletedEvent` 수신 시 `Trip` 엔티티에 요금 정보 업데이트.
    * `PaymentFailedEvent` 수신 시 **보상 트랜잭션** 실행: 운행 완료 상태(`COMPLETED`)를 이전 상태로 롤백.
4.  **여정 상세 조회 (API Endpoint - `/api/trips/{tripId}`):**
    * `GET /`: 여정 기본 정보 조회 후, **User Service** 및 **Driver Service**를 병렬/비동기 호출하여 사용자/기사 상세 정보를 조합하여 반환.
5.  **실시간 위치 추적 (`TripEventConsumer` → `TripService` → `TrackingWebSocketHandler`):**
    * `location_events` 토픽에서 `DriverLocationUpdatedEvent` 수신.
    * 해당 기사가 운행 중인 여정을 찾아, 해당 여정을 추적 중인 승객의 WebSocket 세션으로 위치 정보 전달 (`WebSocketHandler`).

## 기술 스택

* **Language & Framework:** Java, Spring Boot, **Spring WebFlux/Reactor**, Spring Web MVC
* **Messaging:** Spring Kafka
* **Database:** Spring Data JPA, MySQL
* **Real-time Communication:** Spring WebSocket
* **Inter-service Communication:** Spring Cloud OpenFeign / **WebClient**
* **Transaction Management:** Spring Transaction
