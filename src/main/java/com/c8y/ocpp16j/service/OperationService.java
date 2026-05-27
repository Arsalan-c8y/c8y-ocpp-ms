package com.c8y.ocpp16j.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.c8y.ocpp16j.websocket.ChargePointSessionRegistry;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.devicecontrol.OperationCollection;
import com.cumulocity.sdk.client.devicecontrol.OperationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Scheduled service that polls Cumulocity for PENDING operations and dispatches them
 * as OCPP 1.6J commands to connected charge points via WebSocket.
 *
 * Workflow:
 *   1. Every 5 seconds (configurable via ocpp.operation.poll.interval), polls for PENDING
 *      operations across all supported fragment types for each tenant with active sessions.
 *   2. For each PENDING operation whose target device has an open WebSocket session:
 *      a. Marks the operation as EXECUTING
 *      b. Builds the OCPP Call JSON array [2, messageId, action, payload]
 *      c. Sends it over WebSocket
 *      d. Marks the operation as SUCCESSFUL (or FAILED on error)
 *   3. If the target device has no active session, the operation is silently skipped
 *      (it remains PENDING for the next poll cycle or for a deployed instance to pick up).
 *
 * Supported Cumulocity operation fragments → OCPP actions:
 *   - c8y_StopCharging      → RemoteStopTransaction
 *   - c8y_StartCharging     → RemoteStartTransaction
 *   - c8y_Restart           → Reset
 *   - c8y_UnlockConnector   → UnlockConnector
 *   - c8y_ChangeAvailability→ ChangeAvailability
 *   - c8y_GetConfiguration  → GetConfiguration
 *   - c8y_TriggerMessage    → TriggerMessage
 *
 * Each dispatch is individually try-catch guarded so one failing operation never blocks others.
 */
@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);

    // Cumulocity operation fragment names
    private static final String STOP_CHARGING = "c8y_StopCharging";
    private static final String START_CHARGING = "c8y_StartCharging";
    private static final String RESET = "c8y_Restart";
    private static final String UNLOCK_CONNECTOR = "c8y_UnlockConnector";
    private static final String CHANGE_AVAILABILITY = "c8y_ChangeAvailability";
    private static final String GET_CONFIGURATION = "c8y_GetConfiguration";
    private static final String TRIGGER_MESSAGE = "c8y_TriggerMessage";

    private static final List<String> SUPPORTED_FRAGMENTS = List.of(
            STOP_CHARGING, START_CHARGING, RESET, UNLOCK_CONNECTOR,
            CHANGE_AVAILABILITY, GET_CONFIGURATION, TRIGGER_MESSAGE
    );

    private final DeviceControlApi deviceControlApi;
    private final ChargePointSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final MicroserviceSubscriptionsService subscriptionsService;

    public OperationService(DeviceControlApi deviceControlApi,
                            ChargePointSessionRegistry sessionRegistry,
                            ObjectMapper objectMapper,
                            MicroserviceSubscriptionsService subscriptionsService) {
        this.deviceControlApi = deviceControlApi;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.subscriptionsService = subscriptionsService;
    }

    @Scheduled(fixedDelayString = "${ocpp.operation.poll.interval:5000}")
    public void pollForOperations() {
        Set<String> activeTenants = sessionRegistry.getActiveTenants();
        if (activeTenants.isEmpty()) {
            log.trace("No active tenants in registry, skipping operation poll.");
            return;
        }

        log.debug("Polling operations for {} active tenants...", activeTenants.size());

        for (String tenant : activeTenants) {
            subscriptionsService.callForTenant(tenant, () -> {
                try {
                    for (String fragment : SUPPORTED_FRAGMENTS) {
                        pollForFragment(fragment);
                    }
                } catch (Exception e) {
                    log.error("Error polling for operations for tenant {}: {}", tenant, e.getMessage(), e);
                }
                return null;
            });
        }
    }

    private void pollForFragment(String fragmentType) {
        OperationFilter filter = new OperationFilter()
                .byStatus(OperationStatus.PENDING)
                .byFragmentType(fragmentType);

        OperationCollection operationCollection = deviceControlApi.getOperationsByFilter(filter);
        List<OperationRepresentation> ops = new java.util.ArrayList<>();
        operationCollection.get().allPages().forEach(ops::add);
        if (!ops.isEmpty()) {
            log.info("Found {} PENDING {} operations", ops.size(), fragmentType);
            for (OperationRepresentation operation : ops) {
                try {
                    dispatchOperation(fragmentType, operation);
                } catch (Exception e) {
                    log.error("Unexpected error dispatching {} operationId={}: {}",
                            fragmentType, operation.getId().getValue(), e.getMessage(), e);
                }
            }
        }
    }

    private void dispatchOperation(String fragmentType, OperationRepresentation operation) {
        String deviceId = operation.getDeviceId().getValue();

        WebSocketSession session = sessionRegistry.getSession(deviceId);
        if (session == null || !session.isOpen()) {
            log.debug("Skipping {} for deviceId={}: no active WebSocket session", fragmentType, deviceId);
            return;
        }

        log.info("Processing {} operation for deviceId={}, operationId={}",
                fragmentType, deviceId, operation.getId().getValue());

        try {
            operation.setStatus(OperationStatus.EXECUTING.name());
            deviceControlApi.update(operation);
        } catch (Exception e) {
            log.error("Failed to mark operation EXECUTING for deviceId={}, operationId={}: {}",
                    deviceId, operation.getId().getValue(), e.getMessage());
            return;
        }

        try {
            String ocppPayload = buildOcppCall(fragmentType, operation);
            session.sendMessage(new TextMessage(ocppPayload));
            log.info("Sent OCPP command for {} to deviceId={}, operationId={}",
                    fragmentType, deviceId, operation.getId().getValue());

            operation.setStatus(OperationStatus.SUCCESSFUL.name());
            deviceControlApi.update(operation);
        } catch (IOException e) {
            log.error("Failed to send {} to deviceId={}: {}", fragmentType, deviceId, e.getMessage());
            operation.setStatus(OperationStatus.FAILED.name());
            operation.setFailureReason("WebSocket send failed: " + e.getMessage());
            deviceControlApi.update(operation);
        } catch (Exception e) {
            log.error("Unexpected failure processing {} for deviceId={}: {}", fragmentType, deviceId, e.getMessage());
            try {
                operation.setStatus(OperationStatus.FAILED.name());
                operation.setFailureReason("Unexpected error: " + e.getMessage());
                deviceControlApi.update(operation);
            } catch (Exception ex) {
                log.error("Could not mark operation FAILED for deviceId={}: {}", deviceId, ex.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String buildOcppCall(String fragmentType, OperationRepresentation operation) throws IOException {
        String messageId = UUID.randomUUID().toString();
        String action;
        Map<String, Object> payload;

        Object fragment = operation.get(fragmentType);
        Map<String, Object> fragmentData = (fragment instanceof Map) ? (Map<String, Object>) fragment : java.util.Collections.emptyMap();

        if (STOP_CHARGING.equals(fragmentType)) {
            action = "RemoteStopTransaction";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("transactionId", extractInt(fragmentData, "transactionId", 0));
            payload = p;
        } else if (START_CHARGING.equals(fragmentType)) {
            action = "RemoteStartTransaction";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("idTag", extractString(fragmentData, "idTag", "DEFAULT_TAG"));
            p.put("connectorId", extractInt(fragmentData, "connectorId", 1));
            payload = p;
        } else if (RESET.equals(fragmentType)) {
            action = "Reset";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("type", extractString(fragmentData, "type", "Soft"));
            payload = p;
        } else if (UNLOCK_CONNECTOR.equals(fragmentType)) {
            action = "UnlockConnector";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("connectorId", extractInt(fragmentData, "connectorId", 1));
            payload = p;
        } else if (CHANGE_AVAILABILITY.equals(fragmentType)) {
            action = "ChangeAvailability";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("connectorId", extractInt(fragmentData, "connectorId", 1));
            p.put("type", extractString(fragmentData, "type", "Operative"));
            payload = p;
        } else if (GET_CONFIGURATION.equals(fragmentType)) {
            action = "GetConfiguration";
            Object keys = fragmentData.get("key");
            Map<String, Object> p = new java.util.HashMap<>();
            if (keys instanceof List) {
                p.put("key", keys);
            }
            payload = p;
        } else if (TRIGGER_MESSAGE.equals(fragmentType)) {
            action = "TriggerMessage";
            String requestedMessage = extractString(fragmentData, "requestedMessage", "StatusNotification");
            int connectorId = extractInt(fragmentData, "connectorId", 0);
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("requestedMessage", requestedMessage);
            if (connectorId > 0) {
                p.put("connectorId", connectorId);
            }
            payload = p;
        } else {
            action = "DataTransfer";
            Map<String, Object> p = new java.util.HashMap<>();
            p.put("vendorId", "com.cumulocity");
            p.put("data", fragmentData.toString());
            payload = p;
        }

        List<Object> ocppCall = new java.util.ArrayList<>();
        ocppCall.add(2);
        ocppCall.add(messageId);
        ocppCall.add(action);
        ocppCall.add(payload);
        return objectMapper.writeValueAsString(ocppCall);
    }

    private int extractInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private String extractString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val instanceof String) return (String) val;
        return defaultValue;
    }
}
