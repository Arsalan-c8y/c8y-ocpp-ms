package com.c8y.ocpp16j.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.c8y.ocpp16j.websocket.ChargePointSessionRegistry;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.devicecontrol.OperationCollection;
import com.cumulocity.sdk.client.devicecontrol.OperationFilter;
import com.cumulocity.sdk.client.devicecontrol.PagedOperationCollectionRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OperationServiceTest {

    @Mock
    private DeviceControlApi deviceControlApi;

    @Mock
    private ChargePointSessionRegistry sessionRegistry;

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private WebSocketSession webSocketSession;

    @Mock
    private OperationCollection operationCollection;

    @Mock
    private PagedOperationCollectionRepresentation pagedCollection;

    private ObjectMapper objectMapper = new ObjectMapper();
    private OperationService service;

    @BeforeEach
    void setUp() {
        service = new OperationService(deviceControlApi, sessionRegistry, objectMapper, subscriptionsService);
    }

    @Nested
    @DisplayName("Operation Polling")
    class PollingTests {

        @Test
        @DisplayName("should skip polling when no active sessions")
        void shouldSkipWhenNoActiveSessions() {
            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of());

            service.pollForOperations();

            verifyNoInteractions(deviceControlApi);
            verifyNoInteractions(subscriptionsService);
        }

        @Test
        @DisplayName("should poll for each supported fragment type")
        void shouldPollForAllFragmentTypes() {
            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            // callForTenant should execute the callable
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any(OperationFilter.class))).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            when(pagedCollection.allPages()).thenReturn(List.of());

            service.pollForOperations();

            // Should query for 7 fragment types
            verify(deviceControlApi, times(7)).getOperationsByFilter(any(OperationFilter.class));
        }
    }

    @Nested
    @DisplayName("RemoteStopTransaction (c8y_StopCharging)")
    class StopChargingTests {

        @Test
        @DisplayName("should send RemoteStopTransaction and mark SUCCESSFUL")
        void shouldSendRemoteStopAndMarkSuccess() throws Exception {
            OperationRepresentation op = createOperation("device-001", "c8y_StopCharging",
                    Map.of("transactionId", 42));

            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any())).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            // Return the operation only for the first query (c8y_StopCharging), empty for rest
            when(pagedCollection.allPages()).thenReturn(List.of(op), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
            when(sessionRegistry.getSession("device-001")).thenReturn(webSocketSession);
            when(webSocketSession.isOpen()).thenReturn(true);

            service.pollForOperations();

            // Verify WebSocket message sent
            ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(webSocketSession).sendMessage(msgCaptor.capture());

            String sent = msgCaptor.getValue().getPayload();
            List<?> ocppCall = objectMapper.readValue(sent, List.class);
            assertThat(ocppCall.get(0)).isEqualTo(2); // CALL
            assertThat(ocppCall.get(2)).isEqualTo("RemoteStopTransaction");

            @SuppressWarnings("unchecked")
            Map<String, Object> callPayload = (Map<String, Object>) ocppCall.get(3);
            assertThat(callPayload.get("transactionId")).isEqualTo(42);

            // Verify operation status updates: EXECUTING then SUCCESSFUL
            verify(deviceControlApi, times(2)).update(op);
        }

        @Test
        @DisplayName("should skip operation when device not connected")
        void shouldSkipWhenDeviceNotConnected() throws Exception {
            OperationRepresentation op = createOperation("device-001", "c8y_StopCharging", Map.of());

            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any())).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            when(pagedCollection.allPages()).thenReturn(List.of(op), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
            when(sessionRegistry.getSession("device-001")).thenReturn(null);

            service.pollForOperations();

            // Should NOT send any message or update operation
            verify(webSocketSession, never()).sendMessage(any());
            verify(deviceControlApi, never()).update(any());
        }
    }

    @Nested
    @DisplayName("RemoteStartTransaction (c8y_StartCharging)")
    class StartChargingTests {

        @Test
        @DisplayName("should send RemoteStartTransaction with idTag and connectorId")
        void shouldSendRemoteStart() throws Exception {
            OperationRepresentation op = createOperation("device-001", "c8y_StartCharging",
                    Map.of("idTag", "RFID-XYZ", "connectorId", 2));

            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any())).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            // Return op on second call (c8y_StartCharging is second in list)
            when(pagedCollection.allPages()).thenReturn(List.of(), List.of(op), List.of(),
                    List.of(), List.of(), List.of(), List.of());
            when(sessionRegistry.getSession("device-001")).thenReturn(webSocketSession);
            when(webSocketSession.isOpen()).thenReturn(true);

            service.pollForOperations();

            ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(webSocketSession).sendMessage(msgCaptor.capture());

            List<?> ocppCall = objectMapper.readValue(msgCaptor.getValue().getPayload(), List.class);
            assertThat(ocppCall.get(2)).isEqualTo("RemoteStartTransaction");

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ocppCall.get(3);
            assertThat(payload.get("idTag")).isEqualTo("RFID-XYZ");
            assertThat(payload.get("connectorId")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Reset (c8y_Restart)")
    class ResetTests {

        @Test
        @DisplayName("should send Reset with type Hard")
        void shouldSendHardReset() throws Exception {
            OperationRepresentation op = createOperation("device-001", "c8y_Restart",
                    Map.of("type", "Hard"));

            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any())).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            when(pagedCollection.allPages()).thenReturn(List.of(), List.of(), List.of(op),
                    List.of(), List.of(), List.of(), List.of());
            when(sessionRegistry.getSession("device-001")).thenReturn(webSocketSession);
            when(webSocketSession.isOpen()).thenReturn(true);

            service.pollForOperations();

            ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(webSocketSession).sendMessage(msgCaptor.capture());

            List<?> ocppCall = objectMapper.readValue(msgCaptor.getValue().getPayload(), List.class);
            assertThat(ocppCall.get(2)).isEqualTo("Reset");

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ocppCall.get(3);
            assertThat(payload.get("type")).isEqualTo("Hard");
        }
    }

    @Nested
    @DisplayName("UnlockConnector (c8y_UnlockConnector)")
    class UnlockTests {

        @Test
        @DisplayName("should send UnlockConnector")
        void shouldSendUnlock() throws Exception {
            OperationRepresentation op = createOperation("device-001", "c8y_UnlockConnector",
                    Map.of("connectorId", 1));

            when(sessionRegistry.getActiveTenants()).thenReturn(Set.of("t12345"));
            when(subscriptionsService.callForTenant(eq("t12345"), any())).thenAnswer(inv -> {
                var callable = inv.getArgument(1, java.util.concurrent.Callable.class);
                return callable.call();
            });
            when(deviceControlApi.getOperationsByFilter(any())).thenReturn(operationCollection);
            when(operationCollection.get()).thenReturn(pagedCollection);
            when(pagedCollection.allPages()).thenReturn(List.of(), List.of(), List.of(),
                    List.of(op), List.of(), List.of(), List.of());
            when(sessionRegistry.getSession("device-001")).thenReturn(webSocketSession);
            when(webSocketSession.isOpen()).thenReturn(true);

            service.pollForOperations();

            ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(webSocketSession).sendMessage(msgCaptor.capture());

            List<?> ocppCall = objectMapper.readValue(msgCaptor.getValue().getPayload(), List.class);
            assertThat(ocppCall.get(2)).isEqualTo("UnlockConnector");
        }
    }

    // Helper to create an OperationRepresentation
    private OperationRepresentation createOperation(String deviceId, String fragment, Map<String, Object> data) {
        OperationRepresentation op = new OperationRepresentation();
        op.setDeviceId(GId.asGId(deviceId));
        op.setId(GId.asGId("op-" + System.nanoTime()));
        op.setStatus(OperationStatus.PENDING.name());
        op.set(data, fragment);
        return op;
    }
}
