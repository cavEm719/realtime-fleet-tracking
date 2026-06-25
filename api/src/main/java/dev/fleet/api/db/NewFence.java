package dev.fleet.api.db;

import dev.fleet.api.FenceRequest;

public final class NewFence {
    private Long id;
    private final long tenantId;
    private final FenceRequest request;

    public NewFence(long tenantId, FenceRequest request) {
        this.tenantId = tenantId;
        this.request = request;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public FenceRequest getRequest() {
        return request;
    }
}
