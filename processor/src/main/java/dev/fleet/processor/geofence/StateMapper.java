package dev.fleet.processor.geofence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StateMapper extends ConfirmedStateLoader {
    @Override
    List<ConfirmedState> load(@Param("assets") List<AssetRef> assets);

    int updateState(@Param("transition") Transition transition);

    int insertStateIfMissing(@Param("transition") Transition transition);

    int insertEvent(@Param("transition") Transition transition);

    record EventCommit(long createdAtMicros, long id) {}

    EventCommit eventCommit(@Param("eventId") String eventId);

    int insertOutbox(
            @Param("transition") Transition transition,
            @Param("payload") String payload);
}
