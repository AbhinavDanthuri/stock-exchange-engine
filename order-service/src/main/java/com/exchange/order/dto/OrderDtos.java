package com.exchange.order.dto;

import com.exchange.common.events.OrderSide;
import com.exchange.common.events.OrderStatus;
import com.exchange.common.events.OrderType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderDtos {

    public record PlaceOrderRequest(
            @NotBlank @Size(max = 10) String symbol,
            @NotNull OrderSide side,
            @NotNull OrderType type,
            @Positive @Digits(integer = 14, fraction = 4) BigDecimal price, // required iff LIMIT
            @Positive long quantity) {}

    public record OrderResponse(
            String orderId, String symbol, OrderSide side, OrderType type,
            BigDecimal price, long quantity, long filledQuantity,
            OrderStatus status, String rejectReason, Instant createdAt) {}
}
