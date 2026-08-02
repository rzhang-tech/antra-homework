package com.example.book.security;

/**
 * Who the caller is, according to their token.
 *
 * <p>book-service went seven steps without needing this. Its rules were all about <em>roles</em> — only
 * an ADMIN may edit the catalogue, anyone may read it — and a role fits in a username principal with
 * room to spare.
 *
 * <p>Step 9 introduces the first rule about a <em>person</em>: browsing history belongs to whoever did
 * the browsing, and "my history" has to mean one specific row set. That needs an id, and book-service
 * cannot look one up — it has no users table and no route to one (Database-per-Service, Step 5a). The
 * id therefore arrives in the {@code uid} claim, exactly as it does in order-service and
 * payment-service, and becomes the principal here.
 *
 * <p>The identity used for a partition key is the one from the <em>token</em>, never one from a path
 * variable or a header. {@code GET /api/books/me/history} has no user id in its URL on purpose: an
 * endpoint that took one would have to check that the caller may read it, and the check that is never
 * written is the check that is never wrong.
 */
public record AuthenticatedUser(Long id, String username, String role) {
}
