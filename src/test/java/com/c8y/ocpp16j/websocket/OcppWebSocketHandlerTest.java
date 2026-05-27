package com.c8y.ocpp16j.websocket;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.c8y.ocpp16j.service.DeviceMessageService;
import com.c8y.ocpp16j.service.MeasurementService;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OcppWebSocketHandlerTest {

    @Mock private ChargePointSessionRegistry sessionRegistry;
    @Mock private MeasurementService measurementService;
    @Mock private DeviceMessageService deviceMessageService;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private WebSocketSession session;

    private ObjectMapper objectMapper = new ObjectMapper();
    private OcppWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OcppWebSocketHandler(sessionRegistry, measurementService,
                deviceMessageService, objectMapper, subscriptionsService);
    }

    private void setupSession(String chargeBoxId) throws Exception {
        when(session.getUri()).thenReturn(new URI("ws://localhost:8080/ws/" + chargeBoxId));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tenant", "t12345");
        when(session.getAttributes()).thenReturn(attrs);
        when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
            var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
            return callable.call();
        });
    }

    @Test
    @DisplayName("MeterValues should invoke MeasurementService and return empty CallResult")
    void meterValuesShouldInvokeMeasurementService() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-001", "MeterValues",
                Map.of("connectorId", 1, "meterValue", List.of(
                        Map.of("timestamp", "2026-05-26T12:00:00Z", "sampledValue", List.of(
                                Map.of("value", "42500", "measurand", "Energy.Active.Import.Register", "unit", "Wh")
                        ))
                ))
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(measurementService).processMeterValues(eq("device-001"), any());

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        List<?> response = objectMapper.readValue(responseCaptor.getValue().getPayload(), List.class);
        assertThat(response.get(0)).isEqualTo(3); // CallResult
        assertThat(response.get(1)).isEqualTo("msg-001");
    }

    @Test
    @DisplayName("BootNotification should return Accepted with interval")
    void bootNotificationShouldReturnAccepted() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-002", "BootNotification",
                Map.of("chargePointVendor", "ABB", "chargePointModel", "Terra AC")
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processBootNotification(eq("device-001"), any());

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        Map<String, Object> response = objectMapper.readValue(responseCaptor.getValue().getPayload(),
                new TypeReference<List<Object>>() {}).get(2) instanceof Map
                ? (Map<String, Object>) objectMapper.readValue(responseCaptor.getValue().getPayload(),
                new TypeReference<List<Object>>() {}).get(2)
                : Map.of();
        assertThat(response.get("status")).isEqualTo("Accepted");
        assertThat(response.get("interval")).isEqualTo(300);
    }

    @Test
    @DisplayName("Heartbeat should return currentTime")
    void heartbeatShouldReturnCurrentTime() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-003", "Heartbeat", Map.of()
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processHeartbeat("device-001");

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        String responseStr = responseCaptor.getValue().getPayload();
        assertThat(responseStr).contains("currentTime");
    }

    @Test
    @DisplayName("StatusNotification should invoke service and return empty")
    void statusNotificationShouldInvokeService() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-004", "StatusNotification",
                Map.of("connectorId", 1, "status", "Available", "errorCode", "NoError")
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processStatusNotification(eq("device-001"), any());
    }

    @Test
    @DisplayName("StartTransaction should return transactionId and Accepted")
    void startTransactionShouldReturnTransactionId() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-005", "StartTransaction",
                Map.of("connectorId", 1, "idTag", "RFID-001", "meterStart", 0)
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processStartTransaction(eq("device-001"), any());

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        List<?> responseParts = objectMapper.readValue(responseCaptor.getValue().getPayload(), List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> responsePayload = (Map<String, Object>) responseParts.get(2);
        assertThat(responsePayload).containsKey("transactionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> idTagInfo = (Map<String, Object>) responsePayload.get("idTagInfo");
        assertThat(idTagInfo.get("status")).isEqualTo("Accepted");
    }

    @Test
    @DisplayName("StopTransaction should return Accepted")
    void stopTransactionShouldReturnAccepted() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-006", "StopTransaction",
                Map.of("transactionId", 12345, "meterStop", 50000, "reason", "Local")
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processStopTransaction(eq("device-001"), any());

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        List<?> responseParts = objectMapper.readValue(responseCaptor.getValue().getPayload(), List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> responsePayload = (Map<String, Object>) responseParts.get(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> idTagInfo = (Map<String, Object>) responsePayload.get("idTagInfo");
        assertThat(idTagInfo.get("status")).isEqualTo("Accepted");
    }

    @Test
    @DisplayName("Authorize should return Accepted")
    void authorizeShouldReturnAccepted() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-007", "Authorize", Map.of("idTag", "RFID-001")
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(deviceMessageService).processAuthorize(eq("device-001"), any());
    }

    @Test
    @DisplayName("Unknown action should return empty CallResult")
    void unknownActionShouldReturnEmpty() throws Exception {
        setupSession("device-001");

        String payload = objectMapper.writeValueAsString(List.of(
                2, "msg-008", "FirmwareStatusNotification", Map.of("status", "Installed")
        ));

        handler.handleTextMessage(session, new TextMessage(payload));

        ArgumentCaptor<TextMessage> responseCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(responseCaptor.capture());
        List<?> responseParts = objectMapper.readValue(responseCaptor.getValue().getPayload(), List.class);
        assertThat(responseParts.get(0)).isEqualTo(3);
        assertThat(responseParts.get(1)).isEqualTo("msg-008");
    }

    @Test
    @DisplayName("Invalid message (too short) should not send response")
    void invalidMessageShouldNotRespond() throws Exception {
        when(session.getUri()).thenReturn(new URI("ws://localhost:8080/ws/device-001"));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("tenant", "t12345");
        when(session.getAttributes()).thenReturn(attrs);

        String payload = objectMapper.writeValueAsString(List.of(2, "msg-001"));

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(session, never()).sendMessage(any());
    }
}
