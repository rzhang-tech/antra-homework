package com.example.order.dto;

import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponseDto(
        Long id,
        Long userId,
        OrderStatus status,
        BigDecimal totalPrice,
        List<Line> items,
        Instant createdAt
) {

    public record Line(Long bookId, String bookTitle, Integer quantity, BigDecimal unitPrice) {
        static Line from(OrderItem item) {
            return new Line(item.getBookId(), item.getBookTitle(),
                    item.getQuantity(), item.getUnitPrice());
        }
    }

    public static OrderResponseDto from(Order order) {
        return new OrderResponseDto(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getItems().stream().map(Line::from).toList(),
                order.getCreatedAt());
    }
}
