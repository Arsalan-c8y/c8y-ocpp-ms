package com.c8y.ocpp16j.websocket;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-safe, tenant-aware registry of active OCPP WebSocket sessions.
 *
 * Stores sessions keyed by "tenant:chargeBoxId" in a ConcurrentHashMap. Provides:
 *   - register/unregister: called by OcppWebSocketHandler on connect/disconnect
 *   - getSession(chargeBoxId): used by OperationService to send commands to a charger
 *   - getActiveTenants(): used by OperationService to know which tenants to poll
 *   - getActiveDevicesForTenant(tenant): used by ConnectionController for monitoring
 *
 * The registry is the single source of truth for which chargers are currently connected
 * and which tenant they belong to. It ensures the operation poller only queries tenants
 * that actually have connected devices.
 */
@Component
public class ChargePointSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChargePointSessionRegistry.class);

    // Key: "tenant:chargeBoxId", Value: WebSocketSession
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    // Reverse lookup: chargeBoxId → tenant
    private final ConcurrentHashMap<String, String> deviceToTenant = new ConcurrentHashMap<>();

    public void register(String tenant, String chargeBoxId, WebSocketSession session) {
        String key = buildKey(tenant, chargeBoxId);
        sessions.put(key, session);
        deviceToTenant.put(chargeBoxId, tenant);
        log.info("Registered session: tenant={}, chargeBoxId={}, sessionId={}, totalActive={}",
                tenant, chargeBoxId, session.getId(), sessions.size());
    }

    public void unregister(String tenant, String chargeBoxId) {
        String key = buildKey(tenant, chargeBoxId);
        sessions.remove(key);
        deviceToTenant.remove(chargeBoxId);
        log.info("Unregistered session: tenant={}, chargeBoxId={}, totalActive={}",
                tenant, chargeBoxId, sessions.size());
    }

    public WebSocketSession getSession(String chargeBoxId) {
        String tenant = deviceToTenant.get(chargeBoxId);
        if (tenant == null) {
            return null;
        }
        return sessions.get(buildKey(tenant, chargeBoxId));
    }

    public boolean hasSession(String chargeBoxId) {
        return deviceToTenant.containsKey(chargeBoxId);
    }

    /**
     * Returns all active chargeBoxIds for a specific tenant.
     */
    public Set<String> getActiveDevicesForTenant(String tenant) {
        String prefix = tenant + ":";
        return sessions.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    /**
     * Returns a snapshot of all sessions (for monitoring).
     */
    public Map<String, WebSocketSession> getAllSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    public int getTotalActiveCount() {
        return sessions.size();
    }

    public int getActiveCountForTenant(String tenant) {
        return getActiveDevicesForTenant(tenant).size();
    }

    /**
     * Returns all tenants that have at least one active session.
     */
    public Set<String> getActiveTenants() {
        return Set.copyOf(deviceToTenant.values());
    }

    private String buildKey(String tenant, String chargeBoxId) {
        return tenant + ":" + chargeBoxId;
    }
}
