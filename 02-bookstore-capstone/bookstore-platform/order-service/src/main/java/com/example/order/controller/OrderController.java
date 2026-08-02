package com.example.order.controller;

import com.example.order.dto.OrderRequestDto;
import com.example.order.dto.OrderResponseDto;
import com.example.order.dto.PageResponseDto;
import com.example.order.security.AuthenticatedUser;
import com.example.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Place an order.
     *
     * <p>{@code @AuthenticationPrincipal} injects the identity the JWT filter established. The customer
     * is never taken from the request body — a client that could name its own user id could order in
     * someone else's name.
     */
    @PostMapping
    public ResponseEntity<OrderResponseDto> place(@AuthenticationPrincipal AuthenticatedUser customer,
                                                  @Valid @RequestBody OrderRequestDto request) {
        OrderResponseDto created = orderService.place(customer, request);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    /** The caller's own orders. Scoped by the token, not by a query parameter. */
    @GetMapping
    public PageResponseDto<OrderResponseDto> mine(
            @AuthenticationPrincipal AuthenticatedUser customer,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return orderService.findMine(customer, pageable);
    }

    /** Every order on the platform. ADMIN only — enforced by the filter chain. */
    @GetMapping("/all")
    public PageResponseDto<OrderResponseDto> all(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return orderService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public OrderResponseDto get(@AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable Long id) {
        return orderService.findById(caller, id);
    }

    @PutMapping("/{id}/cancel")
    public OrderResponseDto cancel(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable Long id) {
        return orderService.cancel(caller, id);
    }
}
