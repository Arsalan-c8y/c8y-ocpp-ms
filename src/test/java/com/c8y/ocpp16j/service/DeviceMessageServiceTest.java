package com.c8y.ocpp16j.service;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceMessageServiceTest {

    @Mock
    private EventApi eventApi;

    @Mock
    private InventoryApi inventoryApi;

    private DeviceMessageService service;

    @BeforeEach
    void setUp() {
        service = new DeviceMessageService(eventApi, inventoryApi);
    }

    @Test
    @DisplayName("BootNotification should update device and create event")
    void bootNotificationUpdatesDeviceAndCreatesEvent() {
        Map<String, Object> payload = Map.of(
                "chargePointVendor", "Schneider Electric",
                "chargePointModel", "EVLink Pro AC",
                "chargePointSerialNumber", "SN-12345",
                "firmwareVersion", "2.1.0"
        );

        service.processBootNotification("device-001", payload);

        // Verify device inventory update
        ArgumentCaptor<ManagedObjectRepresentation> deviceCaptor = ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryApi).update(deviceCaptor.capture());
        ManagedObjectRepresentation device = deviceCaptor.getValue();
        assertThat(device.getId().getValue()).isEqualTo("device-001");

        @SuppressWarnings("unchecked")
        Map<String, Object> ocppInfo = (Map<String, Object>) device.getProperty("c8y_OCPP");
        assertThat(ocppInfo.get("vendor")).isEqualTo("Schneider Electric");
        assertThat(ocppInfo.get("model")).isEqualTo("EVLink Pro AC");
        assertThat(ocppInfo.get("firmwareVersion")).isEqualTo("2.1.0");

        // Verify event creation
        ArgumentCaptor<EventRepresentation> eventCaptor = ArgumentCaptor.forClass(EventRepresentation.class);
        verify(eventApi).create(eventCaptor.capture());
        EventRepresentation event = eventCaptor.getValue();
        assertThat(event.getType()).isEqualTo("c8y_BootNotification");
        assertThat(event.getText()).contains("Schneider Electric");
    }

    @Test
    @DisplayName("Heartbeat should update device availability")
    void heartbeatUpdatesAvailability() {
        service.processHeartbeat("device-001");

        ArgumentCaptor<ManagedObjectRepresentation> captor = ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryApi).update(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> availability = (Map<String, Object>) captor.getValue().getProperty("c8y_Availability");
        assertThat(availability.get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("StatusNotification should create event and update connector status")
    void statusNotificationCreatesEventAndUpdatesStatus() {
        Map<String, Object> payload = Map.of(
                "connectorId", 1,
                "status", "Charging",
                "errorCode", "NoError"
        );

        service.processStatusNotification("device-001", payload);

        // Verify event
        ArgumentCaptor<EventRepresentation> eventCaptor = ArgumentCaptor.forClass(EventRepresentation.class);
        verify(eventApi).create(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("c8y_StatusNotification");
        assertThat(eventCaptor.getValue().getText()).contains("Charging");

        // Verify inventory update
        ArgumentCaptor<ManagedObjectRepresentation> deviceCaptor = ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryApi).update(deviceCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> connectorStatus = (Map<String, Object>) deviceCaptor.getValue().getProperty("c8y_ConnectorStatus");
        assertThat(connectorStatus.get("status")).isEqualTo("Charging");
        assertThat(connectorStatus.get("connectorId")).isEqualTo(1);
    }

    @Test
    @DisplayName("StartTransaction should create event with session details")
    void startTransactionCreatesEvent() {
        Map<String, Object> payload = Map.of(
                "connectorId", 1,
                "idTag", "RFID-ABC123",
                "meterStart", 50000,
                "timestamp", "2026-05-26T12:00:00Z"
        );

        service.processStartTransaction("device-001", payload);

        ArgumentCaptor<EventRepresentation> captor = ArgumentCaptor.forClass(EventRepresentation.class);
        verify(eventApi).create(captor.capture());
        EventRepresentation event = captor.getValue();
        assertThat(event.getType()).isEqualTo("c8y_StartTransaction");
        assertThat(event.getText()).contains("RFID-ABC123");
        assertThat(event.getText()).contains("50000");
    }

    @Test
    @DisplayName("StopTransaction should create event with final meter and reason")
    void stopTransactionCreatesEvent() {
        Map<String, Object> payload = Map.of(
                "transactionId", 12345,
                "meterStop", 65000,
                "reason", "EVDisconnected",
                "timestamp", "2026-05-26T13:00:00Z"
        );

        service.processStopTransaction("device-001", payload);

        ArgumentCaptor<EventRepresentation> captor = ArgumentCaptor.forClass(EventRepresentation.class);
        verify(eventApi).create(captor.capture());
        EventRepresentation event = captor.getValue();
        assertThat(event.getType()).isEqualTo("c8y_StopTransaction");
        assertThat(event.getText()).contains("12345");
        assertThat(event.getText()).contains("65000");
        assertThat(event.getText()).contains("EVDisconnected");
    }
}
