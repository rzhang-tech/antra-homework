package com.example.payment;

import com.example.payment.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Owns payments. The last of the four services in the assignment's split.
 *
 * <p>The one whose failures cost the most: every other service's mistakes are recoverable by moving
 * rows about, and this one's involve somebody's money.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling   // for PaymentRecoveryJob
@EnableConfigurationProperties(JwtProperties.class)
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
