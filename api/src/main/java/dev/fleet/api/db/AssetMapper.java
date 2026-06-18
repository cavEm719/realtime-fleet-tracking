package dev.fleet.api.db;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AssetMapper {
    int insertBatch(@Param("tenantId") long tenantId, @Param("mmsis") List<Long> mmsis);

    List<Long> findOwned(@Param("tenantId") long tenantId, @Param("mmsis") List<Long> mmsis);
}
