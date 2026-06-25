package dev.fleet.api.db;

public record FenceRow(
        long id,
        long tenantId,
        String name,
        double centerLat,
        double centerLon,
        double enterRadiusM,
        double exitRadiusM,
        long version,
        boolean deleted) {}
