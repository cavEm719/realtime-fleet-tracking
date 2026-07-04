package dev.fleet.processor.geofence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FenceMapper {
    @Select(
            """
            SELECT id, tenant_id, name, center_lat, center_lon,
                   enter_radius_m, exit_radius_m, version
            FROM fence WHERE deleted = false ORDER BY id
            """)
    List<Fence> loadActive();

    @Select(
            """
            SELECT id, tenant_id, name, center_lat, center_lon, enter_radius_m, exit_radius_m, version, deleted
            FROM fence WHERE id = #{id}
            """)
    FenceRow find(long id);

    @Select("SELECT id, version, deleted FROM fence ORDER BY id")
    List<FenceRevision> revisions();
}
