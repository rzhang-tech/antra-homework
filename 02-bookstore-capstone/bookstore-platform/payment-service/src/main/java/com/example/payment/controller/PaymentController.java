package com.example.payment.controller;

import com.example.payment.dto.PaymentRequestDto;
import com.example.payment.dto.PaymentResponseDto;
import com.example.payment.security.AuthenticatedUser;
import com.example.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Pay for an order.
     *
     * <p>Always 201, including for an order that was already paid for: the response body is the same
     * payment either way, and a client that retried after a lost response should see success rather
     * than an error. Distinguishing "created now" from "already existed" would be more informative and
     * is not modelled here — it would need the service to report which happened, and no caller
     * currently acts differently on the two.
     */
    @PostMapping
    public ResponseEntity<PaymentResponseDto> pay(@AuthenticationPrincipal AuthenticatedUser customer,
                                                  @Valid @RequestBody PaymentRequestDto request) {
        PaymentResponseDto payment = paymentService.pay(customer, request);
        return ResponseEntity
                .created(URI.create("/api/payments/" + payment.orderId()))
                .body(payment);
    }

    @GetMapping("/{orderId}")
    public PaymentResponseDto get(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable Long orderId) {
        return paymentService.findByOrderId(caller, orderId);
    }
}
