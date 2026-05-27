package com.c8y.ocpp16j.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.c8y.ocpp16j.websocket.OcppWebSocketHandler;

/**
 * Registers the OCPP WebSocket endpoint at /ws/{chargeBoxId}.
 *
 * Charge points connect using: ws://<host>:8080/ws/<chargeBoxId>
 * where chargeBoxId is typically the Cumulocity managed object ID of the device.
 * AllowedOrigins is set to "*" for development; in production this should be restricted.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OcppWebSocketHandler ocppWebSocketHandler;

    public WebSocketConfig(OcppWebSocketHandler ocppWebSocketHandler) {
        this.ocppWebSocketHandler = ocppWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(ocppWebSocketHandler, "/ws/{chargeBoxId}")
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins("*")
                .setAllowedOriginPatterns("*");
    }
}
