package com.eventhub.controller.order;

import com.eventhub.application.flash.FlashOrderLifecycleApplicationService;
import com.eventhub.controller.order.dto.OrderResponse;
import com.eventhub.controller.order.dto.RefundResponse;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderQueryApi;
import com.eventhub.order.api.model.PaymentResult;
import com.eventhub.security.EventHubAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public final class OrderController {

    private final OrderQueryApi orderQueryApi;
    private final OrderCommandApi orderCommandApi;
    private final FlashOrderLifecycleApplicationService flashOrderLifecycleApplicationService;

    public OrderController(
            OrderQueryApi orderQueryApi,
            OrderCommandApi orderCommandApi,
            FlashOrderLifecycleApplicationService flashOrderLifecycleApplicationService) {
        this.orderQueryApi = orderQueryApi;
        this.orderCommandApi = orderCommandApi;
        this.flashOrderLifecycleApplicationService = flashOrderLifecycleApplicationService;
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long orderId) {
        return OrderResponse.from(orderQueryApi.getOrder(currentUser.userId(), orderId));
    }

    @PostMapping("/{orderId}/pay")
    public PaymentResult pay(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long orderId) {
        return orderCommandApi.pay(currentUser.userId(), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long orderId) {
        return OrderResponse.from(flashOrderLifecycleApplicationService.cancel(currentUser.userId(), orderId));
    }

    @PostMapping("/{orderId}/refund")
    public RefundResponse refund(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long orderId) {
        return RefundResponse.from(flashOrderLifecycleApplicationService.refund(currentUser.userId(), orderId));
    }
}
