package com.example.payment.service;

import com.example.payment.dto.PaymentRequestDto;
import com.example.payment.dto.PaymentResponseDto;
import com.example.payment.security.AuthenticatedUser;

public interface PaymentService {

    PaymentResponseDto pay(AuthenticatedUser customer, PaymentRequestDto request);

    PaymentResponseDto findByOrderId(AuthenticatedUser caller, Long orderId);
}
