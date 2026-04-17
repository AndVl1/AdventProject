package com.andvl.bookshelf.book;

import com.andvl.bookshelf.book.dto.BookResponse;
import com.andvl.bookshelf.book.dto.BookStatsResponse;
import com.andvl.bookshelf.book.dto.CreateBookRequest;
import com.andvl.bookshelf.book.dto.UpdateStatusRequest;
import com.andvl.bookshelf.common.NotFoundException;
import com.andvl.bookshelf.common.ValidationException;
import com.andvl.bookshelf.user.User;
import com.andvl.bookshelf.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    private BookService bookService;

    private User testUser;

    @BeforeEach
    void setUp() {
        bookService = new BookService(bookRepository, userRepository);

        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
    }

    @Test
    void getBooks_Success_ReturnsUserBooks() {
        // Arrange
        Book book1 = createBook(1L, "Book 1", "Author 1", BookStatus.WANT_TO_READ, testUser);
        Book book2 = createBook(2L, "Book 2", "Author 2", BookStatus.READING, testUser);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findAllByOwnerIdOrderByCreatedAtDesc(1L))
            .thenReturn(List.of(book2, book1));

        // Act
        List<BookResponse> books = bookService.getBooks("testuser");

        // Assert
        assertEquals(2, books.size());
        assertEquals("Book 2", books.get(0).title());
        assertEquals("Book 1", books.get(1).title());
    }

    @Test
    void getBooks_UserNotFound_ThrowsUsernameNotFoundException() {
        // Arrange
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
            UsernameNotFoundException.class,
            () -> bookService.getBooks("nonexistent")
        );
    }

    @Test
    void createBook_ValidRequest_ReturnsBookResponse() {
        // Arrange
        CreateBookRequest request = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "WANT_TO_READ"
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> {
            Book book = invocation.getArgument(0);
            book.setId(1L);
            book.setCreatedAt(Instant.now());
            return book;
        });

        // Act
        BookResponse response = bookService.createBook("testuser", request);

        // Assert
        assertNotNull(response);
        assertEquals("Test Book", response.title());
        assertEquals("Test Author", response.author());
        assertEquals("WANT_TO_READ", response.status());
    }

    @Test
    void createBook_InvalidStatus_ThrowsValidationException() {
        // Arrange
        CreateBookRequest request = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "INVALID_STATUS"
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        // Act & Assert
        assertThrows(
            ValidationException.class,
            () -> bookService.createBook("testuser", request)
        );
    }

    @Test
    void updateStatus_ValidRequest_ReturnsUpdatedBook() {
        // Arrange
        Book book = createBook(1L, "Test Book", "Test Author", BookStatus.WANT_TO_READ, testUser);
        UpdateStatusRequest request = new UpdateStatusRequest("READING");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(book));
        when(bookRepository.save(any(Book.class))).thenReturn(book);

        // Act
        BookResponse response = bookService.updateStatus("testuser", 1L, request);

        // Assert
        assertNotNull(response);
        assertEquals("READING", response.status());
    }

    @Test
    void updateStatus_InvalidStatus_ThrowsValidationException() {
        // Arrange
        Book book = createBook(1L, "Test Book", "Test Author", BookStatus.WANT_TO_READ, testUser);
        UpdateStatusRequest request = new UpdateStatusRequest("INVALID_STATUS");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(book));

        // Act & Assert
        assertThrows(
            ValidationException.class,
            () -> bookService.updateStatus("testuser", 1L, request)
        );
    }

    @Test
    void updateStatus_BookNotFound_ThrowsNotFoundException() {
        // Arrange
        UpdateStatusRequest request = new UpdateStatusRequest("READING");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
            NotFoundException.class,
            () -> bookService.updateStatus("testuser", 1L, request)
        );
    }

    @Test
    void deleteBook_ValidRequest_DeletesBook() {
        // Arrange
        Book book = createBook(1L, "Test Book", "Test Author", BookStatus.WANT_TO_READ, testUser);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(book));

        // Act
        bookService.deleteBook("testuser", 1L);

        // Assert
        verify(bookRepository).delete(book);
    }

    @Test
    void deleteBook_BookNotFound_ThrowsNotFoundException() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
            NotFoundException.class,
            () -> bookService.deleteBook("testuser", 1L)
        );
    }

    @Test
    void getStats_Success_ReturnsCorrectStats() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.WANT_TO_READ)).thenReturn(2L);
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.READING)).thenReturn(1L);
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.READ)).thenReturn(3L);
        when(bookRepository.countByOwnerId(1L)).thenReturn(6L);

        // Act
        BookStatsResponse stats = bookService.getStats("testuser");

        // Assert
        assertEquals(2L, stats.wantToRead());
        assertEquals(1L, stats.reading());
        assertEquals(3L, stats.read());
        assertEquals(6L, stats.total());
    }

    @Test
    void getStats_EmptyLibrary_ReturnsZeros() {
        // Arrange
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.WANT_TO_READ)).thenReturn(0L);
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.READING)).thenReturn(0L);
        when(bookRepository.countByOwnerIdAndStatus(1L, BookStatus.READ)).thenReturn(0L);
        when(bookRepository.countByOwnerId(1L)).thenReturn(0L);

        // Act
        BookStatsResponse stats = bookService.getStats("testuser");

        // Assert
        assertEquals(0L, stats.wantToRead());
        assertEquals(0L, stats.reading());
        assertEquals(0L, stats.read());
        assertEquals(0L, stats.total());
    }

    private Book createBook(Long id, String title, String author, BookStatus status, User owner) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthor(author);
        book.setStatus(status);
        book.setOwner(owner);
        book.setCreatedAt(Instant.now());
        return book;
    }
}
