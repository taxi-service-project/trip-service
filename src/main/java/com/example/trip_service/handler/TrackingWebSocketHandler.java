package com.example.trip_service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrackingWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tripId = extractTripId(session);
        sessions.put(tripId, session);
        log.info("승객 위치 추적 연결됨. Trip ID: {}, Session ID: {}", tripId, session.getId());
    }

    public void sendLocationUpdate(String tripId, Object locationData) {
        WebSocketSession session = sessions.get(tripId);
        if (session != null && session.isOpen()) {
            try {
                String messagePayload = objectMapper.writeValueAsString(locationData);
                session.sendMessage(new TextMessage(messagePayload));
            } catch (IOException e) {
                log.error("위치 정보 전송 실패. Trip ID: {}", tripId, e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String tripId = extractTripId(session);
        sessions.remove(tripId);
        log.info("승객 위치 추적 연결 끊김. Trip ID: {}, Status: {}", tripId, status);
    }

    private String extractTripId(WebSocketSession session) {
        URI uri = Objects.requireNonNull(session.getUri());
        String[] pathSegments = uri.getPath().split("/");
        return pathSegments[pathSegments.length - 1];
    }
}