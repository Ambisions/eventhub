package com.eventhub.event.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eventhub.event.repository.EventEntity;
import org.apache.ibatis.annotations.Param;

public interface EventMapper extends BaseMapper<EventEntity> {

    EventEntity selectByIdForUpdate(@Param("eventId") long eventId);

    EventEntity selectPublishedById(@Param("eventId") long eventId);

    EventEntity selectOwnedById(
            @Param("currentUserId") long currentUserId,
            @Param("eventId") long eventId);

    EventEntity selectReviewById(@Param("eventId") long eventId);

    List<EventEntity> selectPublishedPage(
            @Param("offset") long offset,
            @Param("limit") int limit);

    int updateEditableEvent(
            @Param("currentUserId") long currentUserId,
            @Param("eventId") long eventId,
            @Param("title") String title,
            @Param("description") String description);

    int submitEvent(
            @Param("currentUserId") long currentUserId,
            @Param("eventId") long eventId);

    int approveEvent(@Param("eventId") long eventId);

    int rejectEvent(
            @Param("eventId") long eventId,
            @Param("reason") String reason);
}
