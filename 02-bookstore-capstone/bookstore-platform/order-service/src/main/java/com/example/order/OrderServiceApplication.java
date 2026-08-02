package com.example.order;

import com.example.order.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Owns orders, and is the first service that depends on another one at request time.
 *
 * <p>{@code @EnableFeignClients
@EnableScheduling   // for OrderRecoveryJob — the saga's safety net} scans for {@code @FeignClient} interfaces and generates HTTP clients
 * from them at startup.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling   // for OrderRecoveryJob — the saga's safety net
@EnableConfigurationProperties(JwtProperties.class)
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
