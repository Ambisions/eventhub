package com.eventhub.order.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.order.api.model.TimeoutBatchResult;
import com.eventhub.order.mapper.OrderMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderTimeoutService implements OrderTimeoutApi {

    private static final Log LOGGER = LogFactory.getLog(OrderTimeoutService.class);

    private final OrderMapper orderMapper;
    private final OrderTimeoutProcessor orderTimeoutProcessor;
    private final Clock clock;

    public OrderTimeoutService(
            OrderMapper orderMapper,
            OrderTimeoutProcessor orderTimeoutProcessor,
            Clock clock) {
        this.orderMapper = orderMapper;
        this.orderTimeoutProcessor = orderTimeoutProcessor;
        this.clock = clock;
    }

    @Override
    public TimeoutBatchResult processExpiredBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        LocalDateTime scanNow = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        List<Long> orderIds = orderMapper.findExpiredWaitPayIds(scanNow, batchSize);
        int closed = 0;
        for (Long orderId : orderIds) {
            try {
                if (orderTimeoutProcessor.closeExpiredOrder(orderId, scanNow)) {
                    closed++;
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to close expired order " + orderId
                        + " (" + exception.getClass().getSimpleName() + ")", exception);
            }
        }

        TimeoutBatchResult result = new TimeoutBatchResult(orderIds.size(), closed);
        LOGGER.info("Timeout batch completed: scanned=" + result.scanned()
                + ", closed=" + result.closed());
        return result;
    }
}
