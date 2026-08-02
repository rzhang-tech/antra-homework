package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The platform's single front door.
 *
 * <p>Seven services had, between them, six public addresses, four independent answers to "what is
 * allowed without a token", and no shared place to configure CORS. A browser had to know all of them.
 * This service is the one address a client needs, and Step 8 is mostly about what it is <em>not</em>
 * allowed to become while doing that.
 *
 * <h2>What this must never grow</h2>
 *
 * <p>A gateway is the easiest place on a platform to put things, and therefore the easiest place to
 * ruin. It has no database, no domain, and no business rules, and every one of those is a rule rather
 * than an accident of scope:
 *
 * <ul>
 *   <li><strong>No business logic.</strong> A rule that lives here applies to every service by
 *       accident, cannot be tested with the service it belongs to, and turns one deployable into a
 *       dependency of all seven.
 *   <li><strong>No aggregation.</strong> The tempting "one call that returns an order with its books
 *       and its payment" makes the gateway a client of three services with three failure modes and
 *       three timeouts. That is a backend-for-frontend, and it belongs in its own deployable if it is
 *       ever wanted.
 *   <li><strong>No stored state.</strong> Everything it knows comes from the request or the config
 *       server, which is what lets it be scaled to N instances behind a load balancer without any of
 *       them agreeing about anything.
 * </ul>
 *
 * <p>What it legitimately owns: routing, edge authentication, CORS, and — later — rate limiting and
 * request tracing. All of them are properties of <em>the edge</em> rather than of any service.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
