package dev.fleet.processor.geofence;

public record Transition(
        long tenantId,
        long assetId,
        long fenceId,
        long fenceVersion,
        long positionTs,
        Type type,
        GeofenceSettings.Mode mode,
        long ingestedAt,
        long emittedAt) {
    public Transition(
            long tenantId,
            long assetId,
            long fenceId,
            long fenceVersion,
            long positionTs,
            Type type,
            GeofenceSettings.Mode mode) {
        this(tenantId, assetId, fenceId, fenceVersion, positionTs, type, mode, 0, 0);
    }

    public boolean inside() {
        return type == Type.ENTER;
    }

    public String eventId() {
        String canonical = assetId + ":" + fenceId + ":" + type.name() + ":" + positionTs;
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            canonical.getBytes(
                                                    java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public enum Type {
        ENTER,
        EXIT
    }
}
