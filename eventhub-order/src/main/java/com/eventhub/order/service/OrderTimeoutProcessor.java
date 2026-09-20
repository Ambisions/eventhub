package com.eventhub.order.service;

import java.time.LocalDateTime;
import java.util.Objects;

import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.repository.OrderEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTimeoutProcessor {

    private final OrderMapper orderMapper;
    private final InventoryReservationApi inventoryReservationApi;

    public OrderTimeoutProcessor(
            OrderMapper orderMapper,
            InventoryReservationApi inventoryReservationApi) {
        this.orderMapper = orderMapper;
        this.inventoryReservationApi = inventoryReservationApi;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean closeExpiredOrder(long orderId, LocalDateTime scanNow) {
        Objects.requireNonNull(scanNow, "scanNow must not be null");
        OrderEntity order = orderMapper.selectOrderById(orderId);
        if (order == null) {
            return false;
        }

        int affectedRows = orderMapper.closeIfWaitPayAndExpired(orderId, scanNow);
        if (affectedRows == 0) {
            return false;
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Timeout update affected an unexpected number of rows");
        }

        inventoryReservationApi.releaseOne(order.getSessionId());
        return true;
    }
}
