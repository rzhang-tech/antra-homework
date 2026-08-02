package com.example.book.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool that records browsing history, and a rejection policy that is a business decision.
 *
 * <h2>Why this bean exists at all</h2>
 *
 * <p>{@code @Async} without one uses Spring Boot's default executor, and the default is the trap.
 * {@code SimpleAsyncTaskExecutor} does not pool: it starts <em>a new thread per task</em>, without
 * limit. Under the load where recording a view matters — a traffic spike on the catalogue — that is a
 * thread per request until the JVM runs out, and the failure is an OutOfMemoryError in a service whose
 * actual work was a single-row read.
 *
 * <p>So: a bounded pool, and a bounded queue. Both bounds, because a bounded pool with an unbounded
 * queue is unbounded memory with extra steps — the queue simply grows until the heap does not.
 *
 * <h2>The rejection policy is the interesting line</h2>
 *
 * <p>When the pool and the queue are both full, something has to give, and the choice encodes what the
 * feature is worth:
 *
 * <ul>
 *   <li>{@code CallerRunsPolicy} — the request thread does the DynamoDB write itself. Nothing is lost,
 *       and the catalogue read is now as slow as DynamoDB, which is <em>precisely</em> what the
 *       requirement said to avoid. It also applies back-pressure exactly when the service is least able
 *       to absorb it.
 *   <li>{@code AbortPolicy} (the default) — throws into the caller. Same cost, plus an exception to
 *       handle in a read path that has nothing to do with history.
 *   <li>{@code DiscardPolicy} — drop it.
 * </ul>
 *
 * <p><strong>Discard is correct here, and would be indefensible almost anywhere else.</strong>
 * Browsing history is advisory: a missing entry means one row absent from a "recently viewed" list that
 * nobody audits. Losing it is cheaper than slowing down every catalogue read on the platform, which is
 * the entire justification for doing this asynchronously in the first place. The same policy applied to
 * an order, a payment or an audit log would be a defect.
 *
 * <p>What discarding must not be is <em>silent</em>, hence the logged handler below. A feature that
 * quietly stops working under load is indistinguishable from a feature that works.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /** Named, because {@code @Async} without a bean name picks whichever executor it finds. */
    public static final String HISTORY_EXECUTOR = "browsingHistoryExecutor";

    @Bean(HISTORY_EXECUTOR)
    public Executor browsingHistoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Small on purpose. These threads do one network call each and spend it waiting; more of them
        // buys queue depth, not throughput, and this pool is competing with the request threads that
        // are doing the work customers are waiting for.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);

        executor.setThreadNamePrefix("history-");

        // Bounded, and loud about it.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Browsing history write DISCARDED - queue of {} full, {} active threads. "
                                + "History is advisory; the catalogue read was not slowed down.",
                        pool.getQueue().size(), pool.getActiveCount()));

        // Do not hold shutdown open for a queue of history writes. On a rolling deploy the right
        // behaviour is to stop quickly; a few unrecorded views are not worth a slow drain, and the
        // opposite setting is how a pod misses its termination grace period and gets SIGKILLed.
        executor.setWaitForTasksToCompleteOnShutdown(false);

        executor.initialize();
        return executor;
    }
}
