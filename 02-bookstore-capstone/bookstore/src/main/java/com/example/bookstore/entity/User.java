package com.example.bookstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * An application user.
 *
 * <p>Mapped to {@code users}, not {@code user}: USER is a reserved word in PostgreSQL.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * A BCrypt hash — never a password.
     *
     * <p>Named {@code passwordHash} rather than {@code password} deliberately: the name is the reminder.
     * Anywhere this field is read, it should be obvious that comparing it to user input directly is
     * wrong, and that putting it in a response is a leak.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * {@code EnumType.STRING}, not the default {@code ORDINAL}.
     *
     * <p>ORDINAL stores the enum's position — USER as 0, ADMIN as 1 — so inserting a new constant in the
     * middle of the enum later would silently reassign every existing row's role. With a privilege
     * level, that is a security bug, and one that leaves no trace in the data.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
