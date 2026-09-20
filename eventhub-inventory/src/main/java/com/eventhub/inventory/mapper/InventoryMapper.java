package com.eventhub.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eventhub.inventory.repository.InventoryEntity;
import org.apache.ibatis.annotations.Param;

public interface InventoryMapper extends BaseMapper<InventoryEntity> {

    InventoryEntity selectBySessionId(@Param("sessionId") long sessionId);

    int updateAvailableCapacity(@Param("sessionId") long sessionId, @Param("totalStock") int totalStock);

    int lockOne(@Param("sessionId") long sessionId);

    int confirmSoldOne(@Param("sessionId") long sessionId);

    int releaseOne(@Param("sessionId") long sessionId);
}
