package dev.fleet.api;

import dev.fleet.api.db.*;
import dev.fleet.common.FenceChange;
import dev.fleet.common.Json;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FenceService {
    private final FenceCrudMapper fences;
    private final TenantConfigCache tenants;

    public FenceService(FenceCrudMapper fences, TenantConfigCache tenants) {
        this.fences = fences;
        this.tenants = tenants;
    }

    @Transactional
    public FenceRow create(long tenantId, FenceRequest request) {
        tenants.get(tenantId);
        var draft = new NewFence(tenantId, request);
        fences.create(draft);
        return publishChange(tenantId, draft.getId());
    }

    @Transactional
    public FenceRow update(long tenantId, long id, FenceRequest request) {
        if (fences.update(tenantId, id, request) != 1) {
            throw missing();
        }
        return publishChange(tenantId, id);
    }

    @Transactional
    public void delete(long tenantId, long id) {
        if (fences.delete(tenantId, id) != 1) {
            throw missing();
        }
        publishChange(tenantId, id);
    }

    private FenceRow publishChange(long tenantId, long id) {
        var row = fences.find(tenantId, id);
        var change = new FenceChange(row.tenantId(), row.id(), row.version(), row.deleted());
        fences.changeOutbox(tenantId, id, Json.write(change));
        return row;
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Active fence not found for tenant");
    }
}
