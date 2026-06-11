package dev.fleet.common;

public record FenceChange(long tenantId, long fenceId, long version, boolean deleted) {}
