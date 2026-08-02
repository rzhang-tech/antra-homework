package com.example.order.service;

import com.example.order.dto.OrderRequestDto;
import com.example.order.dto.OrderResponseDto;
import com.example.order.dto.PageResponseDto;
import com.example.order.security.AuthenticatedUser;
import org.springframework.data.domain.Pageable;

public interface OrderService {

    OrderResponseDto place(AuthenticatedUser customer, OrderRequestDto request);

    PageResponseDto<OrderResponseDto> findMine(AuthenticatedUser customer, Pageable pageable);

    PageResponseDto<OrderResponseDto> findAll(Pageable pageable);

    OrderResponseDto findById(AuthenticatedUser caller, Long id);

    OrderResponseDto cancel(AuthenticatedUser caller, Long id);

    /**
     * Records that an order has been paid for.
     *
     * <p>Called by payment-service once money has changed hands. Idempotent: an order already PAID is
     * returned unchanged rather than rejected, because the caller retrying is the intended behaviour —
     * see the roll-forward note in {@code PaymentServiceImpl}.
     */
    OrderResponseDto markPaid(AuthenticatedUser caller, Long id);
}
