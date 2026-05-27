package com.c8y.ocpp16j;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the OCPP 1.6J Gateway Cumulocity Microservice.
 *
 * This microservice acts as a centralized WebSocket server for OCPP 1.6 JSON charge points.
 * It bridges the OCPP protocol to the Cumulocity IoT platform by:
 *   - Accepting WebSocket connections from EV chargers at /ws/{chargeBoxId}
 *   - Translating charger-initiated messages (MeterValues, BootNotification, etc.) into
 *     Cumulocity measurements, events, and inventory updates
 *   - Polling Cumulocity for PENDING device control operations and dispatching them
 *     as OCPP commands (RemoteStartTransaction, Reset, etc.) to connected chargers
 *
 * Multi-tenancy is supported via MicroserviceSubscriptionsService.callForTenant().
 * The scheduled operation poller only processes tenants with active WebSocket sessions.
 */
@MicroserviceApplication
@EnableScheduling
public class App {
    public static void main (String[] args) {
        SpringApplication.run(App.class, args);
    }
}