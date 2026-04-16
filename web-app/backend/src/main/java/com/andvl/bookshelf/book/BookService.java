package com.andvl.bookshelf.book;

import com.andvl.bookshelf.book.dto.BookResponse;
import com.andvl.bookshelf.book.dto.BookStatsResponse;
import com.andvl.bookshelf.book.dto.CreateBookRequest;
import com.andvl.bookshelf.book.dto.UpdateStatusRequest;
import com.andvl.bookshelf.common.NotFoundException;
import com.andvl.bookshelf.user.User;
import com.andvl.bookshelf.user.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BookService {

    private final BookRepository bookRepository;
    private final UserRepository userRepository;

    public BookService(BookRepository bookRepository, UserRepository userRepository) {
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
    }

    private User resolveUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    public List<BookResponse> getBooks(String username) {
        User user = resolveUser(username);
        return bookRepository.findAllByOwnerIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public BookResponse createBook(String username, CreateBookRequest request) {
        User user = resolveUser(username);
        BookStatus status;
        try {
            status = BookStatus.valueOf(request.status());
        } catch (IllegalArgumentException e) {
            throw new com.andvl.bookshelf.common.ValidationException("Invalid status: " + request.status());
        }

        Book book = new Book();
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setStatus(status);
        book.setOwner(user);

        return toResponse(bookRepository.save(book));
    }

    @Transactional
    public BookResponse updateStatus(String username, Long bookId, UpdateStatusRequest request) {
        User user = resolveUser(username);
        Book book = bookRepository.findByIdAndOwnerId(bookId, user.getId())
                .orElseThrow(() -> new NotFoundException("Book not found: " + bookId));

        BookStatus status;
        try {
            status = BookStatus.valueOf(request.status());
        } catch (IllegalArgumentException e) {
            throw new com.andvl.bookshelf.common.ValidationException("Invalid status: " + request.status());
        }

        book.setStatus(status);
        return toResponse(bookRepository.save(book));
    }

    @Transactional
    public void deleteBook(String username, Long bookId) {
        User user = resolveUser(username);
        Book book = bookRepository.findByIdAndOwnerId(bookId, user.getId())
                .orElseThrow(() -> new NotFoundException("Book not found: " + bookId));
        bookRepository.delete(book);
    }

    public BookStatsResponse getStats(String username) {
        User user = resolveUser(username);
        long wantToRead = bookRepository.countByOwnerIdAndStatus(user.getId(), BookStatus.WANT_TO_READ);
        long reading = bookRepository.countByOwnerIdAndStatus(user.getId(), BookStatus.READING);
        long read = bookRepository.countByOwnerIdAndStatus(user.getId(), BookStatus.READ);
        long total = bookRepository.countByOwnerId(user.getId());
        return new BookStatsResponse(wantToRead, reading, read, total);
    }

    private BookResponse toResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getStatus().name(),
                book.getCreatedAt()
        );
    }
}
