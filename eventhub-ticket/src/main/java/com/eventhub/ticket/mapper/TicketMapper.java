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

    int markRefundedIfValid(
            @Param("orderId") long orderId,
            @Param("userId") long userId,
            @Param("sessionId") long sessionId);

    TicketEntity selectRefundStateByOrderId(@Param("orderId") long orderId);
}
