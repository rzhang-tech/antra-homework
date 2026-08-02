package com.example.payment.client;

import com.example.payment.dto.OrderSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * payment-service's view of order-service.
 *
 * <p>Two calls with very different characters. The GET is a read that decides whether to charge at all.
 * The PUT happens <em>after</em> money has moved, which changes what failure means: there is no longer
 * an option to abandon the operation, only to keep trying to finish it.
 */
@FeignClient(name = "order-service", url = "${app.order-service.url}")
public interface OrderClient {

    /** The order's owner, status and amount. Read before charging. */
    @GetMapping("/api/orders/{id}")
    OrderSnapshot findById(@PathVariable("id") Long id);

    /** Records the payment against the order. Idempotent on order-service's side, and it must be. */
    @PutMapping("/api/orders/{id}/pay")
    OrderSnapshot markPaid(@PathVariable("id") Long id);
}
