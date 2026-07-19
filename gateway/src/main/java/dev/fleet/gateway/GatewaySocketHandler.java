package dev.fleet.gateway;

import dev.fleet.common.Json;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

public final class GatewaySocketHandler extends TextWebSocketHandler {
    public record Command(
            String type,
            List<Long> assetIds,
            Boolean all,
            EventHistory.Cursor resumeAfter,
            List<String> seenEventIds) {}

    private final GatewayHub hub;

    public GatewaySocketHandler(GatewayHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        hub.open(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var client = hub.client(session.getId());
        if (client == null) {
            return;
        }
        try {
            var command = Json.read(message.getPayload(), Command.class);
            if (command == null
                    || command.type() == null
                    || (Boolean.TRUE.equals(command.all()) && command.assetIds() != null)) {
                throw new IllegalArgumentException("Supply assetIds or all, not both");
            }
            var assets = command.assetIds() == null ? List.<Long>of() : command.assetIds();
            switch (command.type()) {
                case "subscribe" -> {
                    if (!Boolean.TRUE.equals(command.all()) && assets.isEmpty()) {
                        throw new IllegalArgumentException("Subscription is empty");
                    }
                    if (command.resumeAfter() != null) {
                        hub.resume(
                                client,
                                assets,
                                Boolean.TRUE.equals(command.all()),
                                command.resumeAfter(),
                                command.seenEventIds());
                    } else {
                        if (command.seenEventIds() != null)
                            throw new IllegalArgumentException("seenEventIds requires resumeAfter");
                        hub.subscribe(client, assets, Boolean.TRUE.equals(command.all()));
                        client.control("subscribed", "Subscription accepted");
                    }
                }
                case "unsubscribe" -> {
                    if (command.assetIds() == null && !Boolean.TRUE.equals(command.all())) {
                        hub.unsubscribeAll(client);
                    } else {
                        hub.unsubscribe(client, assets, Boolean.TRUE.equals(command.all()));
                    }
                    client.control("unsubscribed", "Subscription removed");
                }
                default -> throw new IllegalArgumentException("Unknown command type");
            }
        } catch (IllegalArgumentException error) {
            client.control("error", error.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var client = hub.client(session.getId());
        if (client != null) {
            client.terminate("closed");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        var client = hub.client(session.getId());
        if (client != null) {
            client.terminate("transport");
        }
    }
}
