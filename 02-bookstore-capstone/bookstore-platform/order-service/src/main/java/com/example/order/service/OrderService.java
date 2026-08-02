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
}
