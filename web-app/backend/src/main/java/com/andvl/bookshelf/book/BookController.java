package com.andvl.bookshelf.book;

import com.andvl.bookshelf.book.dto.BookResponse;
import com.andvl.bookshelf.book.dto.BookStatsResponse;
import com.andvl.bookshelf.book.dto.CreateBookRequest;
import com.andvl.bookshelf.book.dto.UpdateStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public ResponseEntity<List<BookResponse>> getBooks(Authentication auth) {
        return ResponseEntity.ok(bookService.getBooks(auth.getName()));
    }

    @PostMapping
    public ResponseEntity<BookResponse> createBook(
            @Valid @RequestBody CreateBookRequest request,
            Authentication auth) {
        BookResponse book = bookService.createBook(auth.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(book);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<BookResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStatusRequest request,
            Authentication auth) {
        return ResponseEntity.ok(bookService.updateStatus(auth.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBook(
            @PathVariable Long id,
            Authentication auth) {
        bookService.deleteBook(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<BookStatsResponse> getStats(Authentication auth) {
        return ResponseEntity.ok(bookService.getStats(auth.getName()));
    }
}
