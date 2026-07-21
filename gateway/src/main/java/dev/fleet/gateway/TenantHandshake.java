package dev.fleet.gateway;

import dev.fleet.common.TenantAssignment;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

public final class TenantHandshake implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        try {
            var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            long tenantId = Long.parseLong(query.getFirst("tenantId"));
            String userId = query.getFirst("userId");
            if (tenantId < 1
                    || tenantId > TenantAssignment.TENANT_COUNT
                    || userId == null
                    || userId.isBlank()) {
                throw new IllegalArgumentException("tenantId and userId are required");
            }
            attributes.put("tenantId", tenantId);
            attributes.put("userId", userId);
            return true;
        } catch (IllegalArgumentException error) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Exception error) {}
}
