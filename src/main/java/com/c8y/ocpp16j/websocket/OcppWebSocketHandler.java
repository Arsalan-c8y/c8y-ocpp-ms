package com.c8y.ocpp16j.websocket;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.c8y.ocpp16j.service.DeviceMessageService;
import com.c8y.ocpp16j.service.MeasurementService;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Core WebSocket handler for OCPP 1.6J communication.
 *
 * Lifecycle:
 *   1. Charger connects → afterConnectionEstablished() resolves tenant, registers session
 *   2. Charger sends OCPP Call (messageType=2) → handleTextMessage() parses the JSON array,
 *      dispatches to handleAction(), and always sends back a CallResult (messageType=3)
 *   3. Charger disconnects → afterConnectionClosed() unregisters session
 *
 * All Cumulocity API calls are executed within callForTenant() to ensure the correct
 * tenant context. Every action handler is wrapped in try-catch so a single failure never
 * crashes the WebSocket connection or the microservice.
 *
 * Supported inbound OCPP actions:
 *   - MeterValues → creates measurements (energy, power, current, voltage, temperature, SoC)
 *   - BootNotification → updates device inventory, creates event
 *   - Heartbeat → updates c8y_Availability
 *   - StatusNotification → updates connector status, creates event
 *   - StartTransaction → creates c8y_StartTransaction event
 *   - StopTransaction → creates c8y_StopTransaction event
 *   - Authorize → logs authorization request
 *   - DataTransfer → logs vendor data
 */
@Component
public class OcppWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    /**
     * Advertises the OCPP subprotocols this server supports.
     * Spring's DefaultHandshakeHandler uses this list to negotiate and echo back
     * the matching protocol in the Sec-WebSocket-Protocol response header.
     * Without this, strict OCPP clients drop the connection immediately after the
     * handshake because they require the server to acknowledge the subprotocol.
     */
    @Override
    public List<String> getSubProtocols() {
        return List.of("ocpp1.6", "ocpp1.5");
    }

    private static final Logger log = LoggerFactory.getLogger(OcppWebSocketHandler.class);
    private static final int OCPP_CALL = 2;
    private static final String TENANT_ATTR = "tenant";

    private final ChargePointSessionRegistry sessionRegistry;
    private final MeasurementService measurementService;
    private final DeviceMessageService deviceMessageService;
    private final ObjectMapper objectMapper;
    private final MicroserviceSubscriptionsService subscriptionsService;

    public OcppWebSocketHandler(ChargePointSessionRegistry sessionRegistry,
                                MeasurementService measurementService,
                                DeviceMessageService deviceMessageService,
                                ObjectMapper objectMapper,
                                MicroserviceSubscriptionsService subscriptionsService) {
        this.sessionRegistry = sessionRegistry;
        this.measurementService = measurementService;
        this.deviceMessageService = deviceMessageService;
        this.objectMapper = objectMapper;
        this.subscriptionsService = subscriptionsService;
    }

    private String resolveTenant(WebSocketSession session) {
        Map<String, List<String>> headers = session.getHandshakeHeaders();
        List<String> tenantHeader = headers.get("x-cumulocity-tenant");
        if (tenantHeader != null && !tenantHeader.isEmpty()) {
            return tenantHeader.get(0);
        }
        return subscriptionsService.getAll().iterator().next().getTenant();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String chargeBoxId = extractChargeBoxId(session);
        String tenant = resolveTenant(session);
        
        // Log detailed connection info for debugging
        log.info("=== OCPP WebSocket Connection Established ===");
        log.info("  ChargeBoxId: {}", chargeBoxId);
        log.info("  Tenant: {}", tenant);
        log.info("  SessionId: {}", session.getId());
        log.info("  RemoteAddr: {}", session.getRemoteAddress());
        log.info("  URI: {}", session.getUri());
        log.info("  AcceptedProtocol: {}", session.getAcceptedProtocol());
        log.info("  Extensions: {}", session.getExtensions());
        log.info("  BinaryMessageSizeLimit: {}", session.getBinaryMessageSizeLimit());
        log.info("  TextMessageSizeLimit: {}", session.getTextMessageSizeLimit());
        
        // Log handshake headers (for debugging auth/protocol issues)
        if (session.getHandshakeHeaders() != null) {
            log.info("  Handshake Headers:");
            session.getHandshakeHeaders().forEach((key, values) -> 
                log.info("    {}: {}", key, String.join(", ", values))
            );
        }
        
        session.getAttributes().put(TENANT_ATTR, tenant);
        sessionRegistry.register(tenant, chargeBoxId, session);
        
        log.info("=== Connection registered successfully, awaiting first OCPP message ===");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String chargeBoxId = extractChargeBoxId(session);
        String tenant = getTenantFromSession(session);
        
        // Enhanced logging for close events
        log.info("=== OCPP Connection Closed ===");
        log.info("  ChargeBoxId: {}", chargeBoxId);
        log.info("  Tenant: {}", tenant);
        log.info("  SessionId: {}", session.getId());
        log.info("  CloseStatus: {} (code={})", status, status.getCode());
        log.info("  CloseReason: {}", status.getReason() != null ? status.getReason() : "(none)");
        log.info("  StandardCloseStatus: {}", 
            status.getCode() == 1000 ? "NORMAL" :
            status.getCode() == 1001 ? "GOING_AWAY" :
            status.getCode() == 1006 ? "ABNORMAL (no close frame received)" :
            status.getCode() == 1011 ? "SERVER_ERROR" : "OTHER");
        
        sessionRegistry.unregister(tenant, chargeBoxId);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String chargeBoxId = extractChargeBoxId(session);
        String tenant = getTenantFromSession(session);
        String payload = message.getPayload();
        
        log.info(">>> OCPP Message Received from chargeBoxId={}, tenant={}", chargeBoxId, tenant);
        log.info("    Payload length: {} bytes", payload.length());
        log.debug("    Raw payload: {}", payload);

        List<Object> ocppMessage;
        try {
            ocppMessage = objectMapper.readValue(payload, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse OCPP message from chargeBoxId={}, error={}, payload={}", 
                    chargeBoxId, e.getMessage(), payload, e);
            return;
        }

        if (ocppMessage.size() < 3) {
            log.warn("Invalid OCPP message format from chargeBoxId={}, size={}, expected >= 3", 
                    chargeBoxId, ocppMessage.size());
            return;
        }
        
        int messageType = ((Number) ocppMessage.get(0)).intValue();
        String messageId = (String) ocppMessage.get(1);
        log.info("    MessageType: {} ({}), MessageId: {}", 
                messageType, 
                messageType == 2 ? "Call" : messageType == 3 ? "CallResult" : messageType == 4 ? "CallError" : "Unknown",
                messageId);

        if (messageType == OCPP_CALL && ocppMessage.size() >= 4) {
            String action = (String) ocppMessage.get(2);
            Map<String, Object> actionPayload = (Map<String, Object>) ocppMessage.get(3);

            log.info("    Action: {}", action);
            log.info("<<< Processing OCPP Call: chargeBoxId={}, action={}, messageId={}", chargeBoxId, action, messageId);

            Map<String, Object> response;
            try {
                response = subscriptionsService.callForTenant(tenant, () ->
                        handleAction(chargeBoxId, action, actionPayload));
                if (response == null) response = Map.of();
            } catch (Exception e) {
                log.error("Error handling OCPP action '{}' for chargeBoxId={}: {}", action, chargeBoxId, e.getMessage(), e);
                response = Map.of();
            }

            // Always send CallResult so the charger doesn't time out
            String callResult = objectMapper.writeValueAsString(List.of(3, messageId, response));
            session.sendMessage(new TextMessage(callResult));
            log.info(">>> Sent OCPP CallResult to chargeBoxId={}, messageId={}, responseSize={} bytes", 
                    chargeBoxId, messageId, callResult.length());
            log.debug("    Response payload: {}", callResult);
        } else {
            log.warn("OCPP message type {} from chargeBoxId={} is not a Call (ignored)", messageType, chargeBoxId);
        }
    }

    private Map<String, Object> handleAction(String chargeBoxId, String action, Map<String, Object> payload) {
        if ("MeterValues".equals(action)) {
            try { measurementService.processMeterValues(chargeBoxId, payload); } catch (Exception e) { log.error("MeterValues error for {}: {}", chargeBoxId, e.getMessage()); }
            return java.util.Collections.emptyMap();
        } else if ("BootNotification".equals(action)) {
            try { deviceMessageService.processBootNotification(chargeBoxId, payload); } catch (Exception e) { log.error("BootNotification error for {}: {}", chargeBoxId, e.getMessage()); }
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("status", "Accepted");
            resp.put("currentTime", org.joda.time.DateTime.now().toString());
            resp.put("interval", 300);
            return resp;
        } else if ("Heartbeat".equals(action)) {
            try { deviceMessageService.processHeartbeat(chargeBoxId); } catch (Exception e) { log.error("Heartbeat error for {}: {}", chargeBoxId, e.getMessage()); }
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("currentTime", org.joda.time.DateTime.now().toString());
            return resp;
        } else if ("StatusNotification".equals(action)) {
            try { deviceMessageService.processStatusNotification(chargeBoxId, payload); } catch (Exception e) { log.error("StatusNotification error for {}: {}", chargeBoxId, e.getMessage()); }
            return java.util.Collections.emptyMap();
        } else if ("StartTransaction".equals(action)) {
            try { deviceMessageService.processStartTransaction(chargeBoxId, payload); } catch (Exception e) { log.error("StartTransaction error for {}: {}", chargeBoxId, e.getMessage()); }
            Map<String, Object> idTagInfo = new java.util.HashMap<>();
            idTagInfo.put("status", "Accepted");
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("transactionId", System.currentTimeMillis() % 100000);
            resp.put("idTagInfo", idTagInfo);
            return resp;
        } else if ("StopTransaction".equals(action)) {
            try { deviceMessageService.processStopTransaction(chargeBoxId, payload); } catch (Exception e) { log.error("StopTransaction error for {}: {}", chargeBoxId, e.getMessage()); }
            Map<String, Object> idTagInfo = new java.util.HashMap<>();
            idTagInfo.put("status", "Accepted");
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("idTagInfo", idTagInfo);
            return resp;
        } else if ("Authorize".equals(action)) {
            try { deviceMessageService.processAuthorize(chargeBoxId, payload); } catch (Exception e) { log.error("Authorize error for {}: {}", chargeBoxId, e.getMessage()); }
            Map<String, Object> idTagInfo = new java.util.HashMap<>();
            idTagInfo.put("status", "Accepted");
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("idTagInfo", idTagInfo);
            return resp;
        } else if ("DataTransfer".equals(action)) {
            log.info("DataTransfer from chargeBoxId={}: {}", chargeBoxId, payload);
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("status", "Accepted");
            return resp;
        } else {
            log.info("Unhandled OCPP action '{}' from chargeBoxId={}", action, chargeBoxId);
            return java.util.Collections.emptyMap();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String chargeBoxId = extractChargeBoxId(session);
        String tenant = getTenantFromSession(session);
        String errorDesc = exception.getMessage() != null
                ? exception.getMessage()
                : exception.getClass().getSimpleName();

        log.warn("=== OCPP Transport Error ===");
        log.warn("  ChargeBoxId: {}", chargeBoxId);
        log.warn("  Tenant: {}", tenant);
        log.warn("  SessionId: {}", session.getId());
        log.warn("  ExceptionType: {}", exception.getClass().getName());
        log.warn("  ErrorMessage: {}", errorDesc);
        log.warn("  IsOpen: {}", session.isOpen());
        
        // EOFException / connection reset = client disconnected abruptly (simulator stopped,
        // network dropped, etc.). This is expected behaviour — log at WARN, not ERROR.
        if (exception instanceof java.io.EOFException
                || (exception.getMessage() != null && exception.getMessage().contains("Connection reset"))) {
            log.warn("  Diagnosis: Client disconnected abruptly (EOFException or Connection reset)");
            log.warn("  Common causes: simulator closed before sending any OCPP message, network issue, missing OCPP subprotocol");
        } else {
            log.error("  Unexpected transport error - full stack trace:", exception);
        }
        
        sessionRegistry.unregister(tenant, chargeBoxId);
        log.warn("=== Session unregistered due to transport error ===");
    }

    private String extractChargeBoxId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String getTenantFromSession(WebSocketSession session) {
        Object tenant = session.getAttributes().get(TENANT_ATTR);
        return tenant != null ? (String) tenant : resolveTenant(session);
    }
}
