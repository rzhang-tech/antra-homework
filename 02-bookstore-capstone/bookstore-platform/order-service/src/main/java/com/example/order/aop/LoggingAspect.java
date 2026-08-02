package com.example.order.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The cross-cutting logging concern, in exactly one place.
 *
 * <p>Logging every service call's name, arguments, and elapsed time applies identically to every method
 * and belongs to none of them. Written inline it would be the same six lines repeated across the whole
 * codebase, with the business logic buried in instrumentation. Here it is one class, and the same class
 * is copied unchanged into every microservice in Step 5.
 *
 * <p><strong>Known limitation.</strong> Spring AOP is proxy-based: advice only fires when the call comes
 * in through the proxy. A service method calling {@code this.otherMethod()} bypasses the proxy and is
 * <em>not</em> logged. Same reason {@code @Transactional} does not apply to self-invocation.
 */
@Aspect
@Component
@Slf4j
public class LoggingAspect {

    /** Every public method of every class in the service package. */
    @Pointcut("execution(public * com.example.order.service..*(..))")
    public void serviceLayer() {
    }

    @Around("serviceLayer()")
    public Object logExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();

        if (log.isDebugEnabled()) {
            log.debug("-> {} args={}", method, Arrays.toString(joinPoint.getArgs()));
        }

        long startNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.info("<- {} completed in {} ms", method, elapsedMillis(startNanos));
            return result;
        } catch (Throwable ex) {
            // Log and rethrow: the aspect observes, it does not decide. Turning the exception into an
            // HTTP status is GlobalExceptionHandler's job.
            log.warn("!! {} failed after {} ms: {}: {}", method, elapsedMillis(startNanos),
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
