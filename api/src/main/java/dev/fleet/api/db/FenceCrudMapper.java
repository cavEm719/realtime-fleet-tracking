package dev.fleet.api.db;

import dev.fleet.api.FenceRequest;

import org.apache.ibatis.annotations.Param;

public interface FenceCrudMapper {
    int create(NewFence draft);

    FenceRow find(@Param("tenantId") long tenantId, @Param("id") long id);

    int update(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") FenceRequest request);

    int delete(@Param("tenantId") long tenantId, @Param("id") long id);

    int changeOutbox(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("payload") String payload);
}
