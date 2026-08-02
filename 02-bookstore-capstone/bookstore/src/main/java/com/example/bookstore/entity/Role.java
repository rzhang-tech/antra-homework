package com.example.bookstore.entity;

/**
 * What a user is allowed to do. Step 3c wires these to the endpoints.
 *
 * <p>An enum rather than a free string so an invalid role cannot be persisted, and so the compiler
 * catches a typo that a string comparison would only reveal as a silent authorization failure.
 */
public enum Role {

    /** A registered customer: may order and see their own data. */
    USER,

    /** Staff: may manage the catalog and see everyone's data. */
    ADMIN
}
