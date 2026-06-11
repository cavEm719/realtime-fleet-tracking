package dev.fleet.common;

public final class TenantAssignment {
    public static final int TENANT_COUNT = 3;

    public static long forMmsi(long mmsi) {
        return 1L + Math.floorMod(Long.hashCode(mmsi), TENANT_COUNT);
    }

    private TenantAssignment() {}
}
