package com.eventhub.order.api;

import com.eventhub.order.api.model.TimeoutBatchResult;

public interface OrderTimeoutApi {

    TimeoutBatchResult processExpiredBatch(int batchSize);
}
