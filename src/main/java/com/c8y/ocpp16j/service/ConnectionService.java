package com.c8y.ocpp16j.service;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.c8y.ocpp16j.websocket.ChargePointSessionRegistry;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;

@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ChargePointSessionRegistry sessionRegistry;
    private final ContextService<MicroserviceCredentials> contextService;

    public ConnectionService(ChargePointSessionRegistry sessionRegistry,
                             ContextService<MicroserviceCredentials> contextService) {
        this.sessionRegistry = sessionRegistry;
        this.contextService = contextService;
    }

    /**
     * Returns all active chargeBoxIds for the current tenant context.
     */
    public Set<String> getActiveDevicesForCurrentTenant() {
        String tenant = contextService.getContext().getTenant();
        log.debug("Listing active connections for tenant={}", tenant);
        Set<String> devices = sessionRegistry.getActiveDevicesForTenant(tenant);
        log.info("Found {} active connections for tenant={}", devices.size(), tenant);
        return devices;
    }

    public int getActiveCountForCurrentTenant() {
        String tenant = contextService.getContext().getTenant();
        return sessionRegistry.getActiveCountForTenant(tenant);
    }

    public boolean isDeviceConnected(String chargeBoxId) {
        return sessionRegistry.hasSession(chargeBoxId);
    }
}
