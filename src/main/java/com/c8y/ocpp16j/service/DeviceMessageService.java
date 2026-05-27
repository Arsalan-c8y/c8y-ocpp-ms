package com.c8y.ocpp16j.service;

import java.util.Map;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;

/**
 * Handles device-initiated OCPP messages that are NOT measurements.
 *
 * For each message type, this service performs platform-side effects:
 *   - BootNotification: Updates the managed object with c8y_Hardware, c8y_Firmware,
 *     c8y_OCPP fragments and creates a c8y_BootNotification event.
 *   - Heartbeat: Updates c8y_Availability to confirm the device is alive.
 *   - StatusNotification: Creates a c8y_StatusNotification event and updates
 *     the c8y_ConnectorStatus fragment on the managed object.
 *   - StartTransaction: Creates a c8y_StartTransaction event with connector/idTag/meter info.
 *   - StopTransaction: Creates a c8y_StopTransaction event with final meter and reason.
 *   - Authorize: Logs the authorization request (no event created, always accepts).
 *
 * All Cumulocity API calls are wrapped in try-catch to prevent any single failure
 * from propagating up and disconnecting the charger's WebSocket session.
 */
@Service
public class DeviceMessageService {

    private static final Logger log = LoggerFactory.getLogger(DeviceMessageService.class);

    private final EventApi eventApi;
    private final InventoryApi inventoryApi;

    public DeviceMessageService(EventApi eventApi, InventoryApi inventoryApi) {
        this.eventApi = eventApi;
        this.inventoryApi = inventoryApi;
    }

    public void processBootNotification(String chargeBoxId, Map<String, Object> payload) {
        String vendor = (String) payload.getOrDefault("chargePointVendor", "Unknown");
        String model = (String) payload.getOrDefault("chargePointModel", "Unknown");
        String serial = (String) payload.getOrDefault("chargePointSerialNumber", "");
        String firmware = (String) payload.getOrDefault("firmwareVersion", "");

        // Update device managed object with charger info
        try {
            ManagedObjectRepresentation device = new ManagedObjectRepresentation();
            device.setId(GId.asGId(chargeBoxId));
            device.setProperty("c8y_Hardware", Map.of(
                    "serialNumber", serial,
                    "model", model
            ));
            device.setProperty("c8y_Firmware", Map.of(
                    "version", firmware
            ));
            device.setProperty("c8y_OCPP", Map.of(
                    "vendor", vendor,
                    "model", model,
                    "serialNumber", serial,
                    "firmwareVersion", firmware
            ));
            inventoryApi.update(device);
            log.info("Updated device info for chargeBoxId={}: vendor={}, model={}, fw={}",
                    chargeBoxId, vendor, model, firmware);
        } catch (Exception e) {
            log.error("Failed to update device for BootNotification chargeBoxId={}: {}", chargeBoxId, e.getMessage());
        }

        createEvent(chargeBoxId, "c8y_BootNotification",
                String.format("Charger booted: %s %s (FW: %s)", vendor, model, firmware));
    }

    public void processHeartbeat(String chargeBoxId) {
        log.debug("Heartbeat from chargeBoxId={}", chargeBoxId);
        // Heartbeat confirms the device is alive — update availability
        try {
            ManagedObjectRepresentation device = new ManagedObjectRepresentation();
            device.setId(GId.asGId(chargeBoxId));
            device.setProperty("c8y_Availability", Map.of("status", "AVAILABLE"));
            inventoryApi.update(device);
        } catch (Exception e) {
            log.debug("Failed to update availability for chargeBoxId={}: {}", chargeBoxId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void processStatusNotification(String chargeBoxId, Map<String, Object> payload) {
        int connectorId = payload.get("connectorId") instanceof Number
                ? ((Number) payload.get("connectorId")).intValue() : 0;
        String status = (String) payload.getOrDefault("status", "Unknown");
        String errorCode = (String) payload.getOrDefault("errorCode", "NoError");

        log.info("StatusNotification from chargeBoxId={}: connector={}, status={}, error={}",
                chargeBoxId, connectorId, status, errorCode);

        createEvent(chargeBoxId, "c8y_StatusNotification",
                String.format("Connector %d: %s (error: %s)", connectorId, status, errorCode));

        // Update device with current connector status
        try {
            ManagedObjectRepresentation device = new ManagedObjectRepresentation();
            device.setId(GId.asGId(chargeBoxId));
            device.setProperty("c8y_ConnectorStatus", Map.of(
                    "connectorId", connectorId,
                    "status", status,
                    "errorCode", errorCode
            ));
            inventoryApi.update(device);
        } catch (Exception e) {
            log.debug("Failed to update connector status for chargeBoxId={}: {}", chargeBoxId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void processStartTransaction(String chargeBoxId, Map<String, Object> payload) {
        int connectorId = payload.get("connectorId") instanceof Number
                ? ((Number) payload.get("connectorId")).intValue() : 1;
        String idTag = (String) payload.getOrDefault("idTag", "");
        int meterStart = payload.get("meterStart") instanceof Number
                ? ((Number) payload.get("meterStart")).intValue() : 0;

        log.info("StartTransaction from chargeBoxId={}: connector={}, idTag={}, meterStart={}",
                chargeBoxId, connectorId, idTag, meterStart);

        createEvent(chargeBoxId, "c8y_StartTransaction",
                String.format("Charging started: connector=%d, idTag=%s, meterStart=%d Wh",
                        connectorId, idTag, meterStart));
    }

    @SuppressWarnings("unchecked")
    public void processStopTransaction(String chargeBoxId, Map<String, Object> payload) {
        int transactionId = payload.get("transactionId") instanceof Number
                ? ((Number) payload.get("transactionId")).intValue() : 0;
        int meterStop = payload.get("meterStop") instanceof Number
                ? ((Number) payload.get("meterStop")).intValue() : 0;
        String reason = (String) payload.getOrDefault("reason", "Local");

        log.info("StopTransaction from chargeBoxId={}: txId={}, meterStop={}, reason={}",
                chargeBoxId, transactionId, meterStop, reason);

        createEvent(chargeBoxId, "c8y_StopTransaction",
                String.format("Charging stopped: txId=%d, meterStop=%d Wh, reason=%s",
                        transactionId, meterStop, reason));
    }

    public void processAuthorize(String chargeBoxId, Map<String, Object> payload) {
        String idTag = (String) payload.getOrDefault("idTag", "");
        log.info("Authorize request from chargeBoxId={}: idTag={}", chargeBoxId, idTag);
    }

    private void createEvent(String chargeBoxId, String type, String text) {
        try {
            EventRepresentation event = new EventRepresentation();
            ManagedObjectRepresentation source = new ManagedObjectRepresentation();
            source.setId(GId.asGId(chargeBoxId));
            event.setSource(source);
            event.setType(type);
            event.setText(text);
            event.setDateTime(DateTime.now());
            eventApi.create(event);
            log.info("Created event {} for chargeBoxId={}: {}", type, chargeBoxId, text);
        } catch (Exception e) {
            log.error("Failed to create event {} for chargeBoxId={}: {}", type, chargeBoxId, e.getMessage());
        }
    }
}
