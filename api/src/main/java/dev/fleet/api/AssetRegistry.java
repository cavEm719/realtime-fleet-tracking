package dev.fleet.api;

import dev.fleet.api.db.AssetMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AssetRegistry {
    private final AssetMapper mapper;
    private final TransactionTemplate transactions;
    private final ConcurrentMap<Long, Long> confirmedOwners = new ConcurrentHashMap<>();

    public AssetRegistry(AssetMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void ensureOwned(long tenantId, List<Long> mmsis) {
        var unseen = new ArrayList<Long>();
        for (long mmsi : mmsis) {
            Long owner = confirmedOwners.get(mmsi);
            if (owner == null) {
                unseen.add(mmsi);
            } else if (owner != tenantId) {
                throw foreignOwner();
            }
        }
        if (unseen.isEmpty()) {
            return;
        }
        registerUnseen(tenantId, unseen);
    }

    private void registerUnseen(long tenantId, List<Long> unseen) {
        transactions.executeWithoutResult(
                status -> {
                    mapper.insertBatch(tenantId, unseen);
                    if (mapper.findOwned(tenantId, unseen).size() != unseen.size()) {
                        throw foreignOwner();
                    }
                    // An outer transaction can still roll back; warm the cache only after its
                    // commit.
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    unseen.forEach(mmsi -> confirmedOwners.put(mmsi, tenantId));
                                }
                            });
                });
    }

    private ResponseStatusException foreignOwner() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Asset belongs to another tenant");
    }
}
