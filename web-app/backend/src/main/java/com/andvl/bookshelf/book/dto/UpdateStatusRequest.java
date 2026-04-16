package com.andvl.bookshelf.book.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull String status
) {
}
