package com.example.order.client;

import com.example.order.dto.BookSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * order-service's view of book-service.
 *
 * <p>In the monolith this was {@code bookService.findById(id)} — a method call that could not fail,
 * returned instantly, and shared a transaction with its caller. It is now an HTTP request to another
 * process, which can be slow, can time out, can return 500, and cannot be rolled back. Nothing about
 * the Java syntax below advertises that, which is exactly what makes distributed systems deceptive:
 * the call site looks identical and the failure modes are entirely different.
 *
 * <p>OpenFeign generates the implementation from this interface at startup. The URL comes from
 * {@code app.book-service.url} rather than being hard-coded — Step 8 puts a gateway in front, and
 * Step 10 replaces the host with a Kubernetes service name.
 *
 * <p><strong>The DTO is deliberately not book-service's.</strong> {@link BookSnapshot} declares only the
 * four fields order-service needs and ignores everything else. That keeps book-service free to add,
 * reorder, or rename fields nobody here reads — a shared DTO class would have made every such change a
 * coordinated release (D12).
 */
@FeignClient(name = "book-service", url = "${app.book-service.url}")
public interface BookClient {

    /** Price and stock, as book-service currently believes them. */
    @GetMapping("/api/books/{id}")
    BookSnapshot findById(@PathVariable("id") Long id);

    /**
     * Decrements stock. The write half of the conversation, and the one with no undo.
     *
     * <p>Once this succeeds, book-service has committed a change in its own database. If order-service
     * then fails, there is no shared transaction to roll back — only a compensating call. That is the
     * whole of the saga problem, and 5d takes it seriously.
     */
    @PostMapping("/api/books/{id}/purchase")
    BookSnapshot purchase(@PathVariable("id") Long id, @RequestBody Map<String, Integer> body);
}
