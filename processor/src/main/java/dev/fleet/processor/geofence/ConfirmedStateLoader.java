package dev.fleet.processor.geofence;

import java.util.List;

@FunctionalInterface
public interface ConfirmedStateLoader {
    List<ConfirmedState> load(List<AssetRef> assets);
}
