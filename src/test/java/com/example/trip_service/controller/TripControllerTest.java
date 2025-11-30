package com.example.trip_service.controller;

import com.example.trip_service.dto.CancelTripRequest;
import com.example.trip_service.dto.CompleteTripRequest;
import com.example.trip_service.dto.TripDetailsResponse;
import com.example.trip_service.dto.TripDetailsResponse.DriverInfo;
import com.example.trip_service.dto.TripDetailsResponse.UserInfo;
import com.example.trip_service.entity.TripStatus;
import com.example.trip_service.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TripController.class)
class TripControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TripService tripService;

    @Test
    @DisplayName("기사 도착 처리: 정상 요청 시 204 No Content 반환")
    void driverArrived_Success() throws Exception {
        // Given
        String tripId = "trip-123";
        willDoNothing().given(tripService).driverArrived(tripId);

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/arrive", tripId))
               .andExpect(status().isNoContent());

        then(tripService).should(times(1)).driverArrived(tripId);
    }

    @Test
    @DisplayName("운행 시작 처리: 정상 요청 시 204 No Content 반환")
    void startTrip_Success() throws Exception {
        // Given
        String tripId = "trip-123";
        willDoNothing().given(tripService).startTrip(tripId);

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/start", tripId))
               .andExpect(status().isNoContent());

        then(tripService).should(times(1)).startTrip(tripId);
    }

    @Test
    @DisplayName("운행 종료 처리: 정상 파라미터 요청 시 204 No Content 반환")
    void completeTrip_Success() throws Exception {
        // Given
        String tripId = "trip-123";
        CompleteTripRequest request = new CompleteTripRequest(5000, 600); // 5km, 10분

        willDoNothing().given(tripService).completeTrip(eq(tripId), any(CompleteTripRequest.class));

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/complete", tripId)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isNoContent());

        then(tripService).should(times(1)).completeTrip(eq(tripId), any(CompleteTripRequest.class));
    }

    @Test
    @DisplayName("운행 종료 처리: 운행 거리나 시간이 0 이하이면 400 Bad Request")
    void completeTrip_InvalidInput() throws Exception {
        // Given
        String tripId = "trip-123";
        // 거리 0, 시간 -1 (유효성 위반)
        CompleteTripRequest invalidRequest = new CompleteTripRequest(0, -1);

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/complete", tripId)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(invalidRequest)))
               .andExpect(status().isBadRequest());

        then(tripService).should(never()).completeTrip(anyString(), any());
    }

    @Test
    @DisplayName("여정 취소 처리: 정상 요청(USER/DRIVER) 시 204 No Content 반환")
    void cancelTrip_Success() throws Exception {
        // Given
        String tripId = "trip-123";
        CancelTripRequest request = new CancelTripRequest("USER");

        willDoNothing().given(tripService).cancelTrip(eq(tripId), any(CancelTripRequest.class));

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/cancel", tripId)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isNoContent());

        then(tripService).should(times(1)).cancelTrip(eq(tripId), any(CancelTripRequest.class));
    }

    @Test
    @DisplayName("여정 취소 처리: 취소 주체가 올바르지 않으면(Regex 위반) 400 Bad Request")
    void cancelTrip_InvalidPattern() throws Exception {
        // Given
        String tripId = "trip-123";
        // "ADMIN"은 허용되지 않은 패턴 (USER|DRIVER 만 가능)
        CancelTripRequest invalidRequest = new CancelTripRequest("ADMIN");

        // When & Then
        mockMvc.perform(put("/api/trips/{tripId}/cancel", tripId)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(invalidRequest)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("여정 상세 조회: Mono 반환값을 AsyncDispatch로 처리하여 200 OK와 상세 정보를 반환한다")
    void getTripDetails_Success() throws Exception {
        // Given
        String tripId = "trip-123";
        String userId = "u1";
        LocalDateTime now = LocalDateTime.now();

        TripDetailsResponse response = TripDetailsResponse.builder()
                                                          .tripId(tripId)
                                                          .status(TripStatus.COMPLETED)
                                                          .originAddress("서울역")
                                                          .destinationAddress("강남역")
                                                          .fare(15000)
                                                          .matchedAt(now.minusMinutes(30))
                                                          .startedAt(now.minusMinutes(25))
                                                          .endedAt(now)
                                                          .user(new UserInfo("u1", "홍길동"))
                                                          .driver(new DriverInfo("d1", "김기사", "12가3456", "쏘나타"))
                                                          .build();

        given(tripService.getTripDetails(tripId)).willReturn(response);


        mockMvc.perform(get("/api/trips/{tripId}", tripId)
                       .header("X-User-Id", userId)
                       .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.tripId").value(tripId))
               .andExpect(jsonPath("$.status").value("COMPLETED"))
               .andExpect(jsonPath("$.originAddress").value("서울역"))
               .andExpect(jsonPath("$.user.name").value("홍길동"))
               .andExpect(jsonPath("$.driver.name").value("김기사"));
    }
}