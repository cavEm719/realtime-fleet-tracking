package dev.fleet.common;

public final class RedisKeys {
    public static String latest(long tenantId, long assetId) {
        return "tenant:" + tenantId + ":asset:" + assetId + ":latest";
    }

    public static String geo(long tenantId) {
        return "geo:{" + tenantId + "}";
    }

    public static String channel(long tenantId) {
        return "positions:tenant:" + tenantId;
    }

    public static String ingestBucket(long tenantId) {
        return "tenant:" + tenantId + ":rate:ingest";
    }

    private RedisKeys() {}
}
