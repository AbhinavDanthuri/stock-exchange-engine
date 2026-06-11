package com.exchange.order.controller;

import com.exchange.order.dto.OrderDtos.*;
import com.exchange.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The API gateway validates the JWT and forwards the caller's identity in
 * X-User-Id. Services behind the gateway are not internet-reachable.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED) // 202: matching is asynchronous
    public OrderResponse place(@RequestHeader("X-User-Id") Long userId,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody PlaceOrderRequest req) {
        return service.placeOrder(userId, req, idemKey);
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderId) {
        service.requestCancel(userId, orderId);
    }

    @GetMapping
    public List<OrderResponse> myOrders(@RequestHeader("X-User-Id") Long userId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.myOrders(userId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderId) {
        return service.get(userId, orderId);
    }
}
