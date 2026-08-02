package com.example.payment.client;

import com.example.payment.security.JwtAuthenticationFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Decides what credential every outgoing call carries.
 *
 * <p>Two cases, and the second had to be discovered the hard way:
 *
 * <ol>
 *   <li><strong>Inside a request</strong> — forward the caller's token, so order-service authorises the
 *       real customer rather than this service. One set of rules, evaluated against the actual actor.</li>
 *   <li><strong>Outside a request</strong> — {@link com.example.payment.service.PaymentRecoveryJob} runs
 *       on a timer with no caller and nothing to forward. The first version simply sent no header, and
 *       every recovery attempt came back 401: a mechanism that could not do the one thing it existed
 *       for, failing in a log nobody reads. It now uses {@link ServiceTokenProvider}.</li>
 * </ol>
 *
 * <p>The distinction is not plumbing. Identity propagation covers only work that happens while the
 * caller is still there; anything asynchronous — a scheduled job, a queue consumer, a retry after the
 * customer has gone — needs an identity of its own, and deciding what that identity may do is a
 * security decision.
 */
@Configuration
@RequiredArgsConstructor
public class FeignAuthPropagation {

    private final ServiceTokenProvider serviceTokenProvider;

    @Bean
    public RequestInterceptor authForwardingInterceptor() {
        return (RequestTemplate template) -> {
            String token = callerToken();
            if (token == null) {
                // No request in flight: background work, acting on its own behalf.
                token = serviceTokenProvider.mint();
            }
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        };
    }

    private String callerToken() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object token = servletAttributes.getRequest()
                    .getAttribute(JwtAuthenticationFilter.TOKEN_ATTRIBUTE);
            if (token instanceof String raw && !raw.isBlank()) {
                return raw;
            }
        }
        return null;
    }
}
