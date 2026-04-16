package com.andvl.bookshelf.book.dto;

import java.time.Instant;

public record BookResponse(
        Long id,
        String title,
        String author,
        String status,
        Instant createdAt
) {
}
