package com.example.order.security;

/**
 * Who the caller is, according to their token.
 *
 * <p>book-service only ever needed the role, so a username principal was enough there. An order has to
 * record <em>which</em> customer placed it, and order-service cannot look that up — it has no access to
 * the users table. The id therefore arrives in the {@code uid} claim and becomes the principal here.
 *
 * <p>This is the identity every ownership check uses. "Only the owner may see this order" is decided by
 * comparing {@code order.userId} to this id — never to anything the client sent.
 */
public record AuthenticatedUser(Long id, String username, String role) {
}
