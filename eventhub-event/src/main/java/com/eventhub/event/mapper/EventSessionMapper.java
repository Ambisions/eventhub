package com.eventhub.event.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.event.repository.EventSessionEntity;
import org.apache.ibatis.annotations.Param;

public interface EventSessionMapper extends BaseMapper<EventSessionEntity> {

    EventSessionEntity selectOwnedCheckInSession(
            @Param("organizerUserId") long organizerUserId,
            @Param("sessionId") long sessionId);

    List<EventSessionEntity> selectPublishedByEventId(@Param("eventId") long eventId);

    EventSessionEntity selectPublishedById(@Param("sessionId") long sessionId);

    int updateSession(
            @Param("sessionId") long sessionId,
            @Param("eventId") long eventId,
            @Param("name") String name,
            @Param("venue") String venue,
            @Param("saleMode") SessionSaleMode saleMode,
            @Param("saleStartAt") java.time.LocalDateTime saleStartAt,
            @Param("saleEndAt") java.time.LocalDateTime saleEndAt,
            @Param("startAt") java.time.LocalDateTime startAt,
            @Param("endAt") java.time.LocalDateTime endAt);
}
