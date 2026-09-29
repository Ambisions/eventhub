package com.eventhub.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eventhub.ticket.repository.TicketEntity;
import org.apache.ibatis.annotations.Param;

public interface TicketMapper extends BaseMapper<TicketEntity> {

    TicketEntity selectCheckInTarget(@Param("ticketNo") String ticketNo);

    int markUsedIfValid(
            @Param("ticketNo") String ticketNo,
            @Param("sessionId") long sessionId);

    TicketEntity selectCheckInState(
            @Param("ticketNo") String ticketNo,
            @Param("sessionId") long sessionId);
}
