package com.example.book.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * The author of one or more books.
 */
@Entity
@Table(name = "author")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /**
     * The other side of {@link Book#getAuthor()}.
     *
     * <p>{@code mappedBy = "author"} says Book owns the relation — the foreign key lives in
     * {@code book.author_id}, and this side is only a view of it. Without it, JPA would assume two
     * independent relations and try to create a join table.
     *
     * <p>Reading this collection is what produces the worst N+1 in the project: one query for the
     * authors, then one more per author for their books. See {@code AuthorRepository} for the fix.
     */
    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Book> books = new ArrayList<>();
}
