package com.c8y.ocpp16j.controller;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.c8y.ocpp16j.service.ConnectionService;

@RestController
@RequestMapping("/connections")
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * GET /connections
     * Lists all active WebSocket connections for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listConnections() {
        Set<String> devices = connectionService.getActiveDevicesForCurrentTenant();
        return ResponseEntity.ok(Map.of(
                "activeConnections", devices.size(),
                "devices", devices
        ));
    }

    /**
     * GET /connections/{chargeBoxId}
     * Checks if a specific device has an active connection.
     */
    @GetMapping("/{chargeBoxId}")
    public ResponseEntity<Map<String, Object>> getConnectionStatus(@PathVariable String chargeBoxId) {
        boolean connected = connectionService.isDeviceConnected(chargeBoxId);
        return ResponseEntity.ok(Map.of(
                "chargeBoxId", chargeBoxId,
                "connected", connected
        ));
    }
}
