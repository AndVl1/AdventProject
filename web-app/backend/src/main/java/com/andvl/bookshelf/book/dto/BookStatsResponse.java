package com.andvl.bookshelf.book.dto;

public record BookStatsResponse(
        long wantToRead,
        long reading,
        long read,
        long total
) {
}
