package com.andvl.bookshelf.book;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {

    List<Book> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<Book> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerIdAndStatus(Long ownerId, BookStatus status);

    long countByOwnerId(Long ownerId);
}
