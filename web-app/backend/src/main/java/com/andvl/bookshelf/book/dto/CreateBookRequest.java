package com.andvl.bookshelf.book.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBookRequest(
        @NotBlank @Size(min = 1, max = 255) String title,
        @NotBlank @Size(min = 1, max = 255) String author,
        @NotNull String status
) {
}
