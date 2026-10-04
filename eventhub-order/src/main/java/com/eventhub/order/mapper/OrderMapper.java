package com.eventhub.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eventhub.order.repository.OrderEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderMapper extends BaseMapper<OrderEntity> {

    int insertWaitPay(OrderEntity order);

    OrderEntity selectOrderById(@Param("orderId") long orderId);

    OrderEntity selectByIdAndUserId(
            @Param("orderId") long orderId,
            @Param("userId") long userId);

    int markPaidIfWaitPayAndNotExpired(
            @Param("orderId") long orderId,
            @Param("userId") long userId,
            @Param("paymentNow") LocalDateTime paymentNow);

    int cancelIfWaitPay(
            @Param("orderId") long orderId,
            @Param("userId") long userId);

    int markRefundedIfPaid(
            @Param("orderId") long orderId,
            @Param("userId") long userId,
            @Param("sessionId") long sessionId);

    List<Long> findExpiredWaitPayIds(
            @Param("scanNow") LocalDateTime scanNow,
            @Param("batchSize") int batchSize);

    int closeIfWaitPayAndExpired(
            @Param("orderId") long orderId,
            @Param("scanNow") LocalDateTime scanNow);
}
