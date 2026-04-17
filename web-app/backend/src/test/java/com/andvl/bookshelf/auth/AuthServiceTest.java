package com.andvl.bookshelf.auth;

import com.andvl.bookshelf.auth.dto.LoginRequest;
import com.andvl.bookshelf.auth.dto.RegisterRequest;
import com.andvl.bookshelf.auth.dto.TokenResponse;
import com.andvl.bookshelf.common.ConflictException;
import com.andvl.bookshelf.security.JwtService;
import com.andvl.bookshelf.user.User;
import com.andvl.bookshelf.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void register_Success_ReturnsToken() {
        // Arrange
        RegisterRequest request = new RegisterRequest("testuser", "password123");
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
        when(jwtService.generateToken("testuser")).thenReturn("jwt-token");

        // Act
        TokenResponse response = authService.register(request);

        // Assert
        assertNotNull(response);
        assertEquals("jwt-token", response.token());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_ExistingUsername_ThrowsConflictException() {
        // Arrange
        RegisterRequest request = new RegisterRequest("existinguser", "password123");
        when(userRepository.existsByUsername("existinguser")).thenReturn(true);

        // Act & Assert
        ConflictException exception = assertThrows(
            ConflictException.class,
            () -> authService.register(request)
        );

        assertTrue(exception.getMessage().contains("Username already taken"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_ValidCredentials_ReturnsToken() {
        // Arrange
        LoginRequest request = new LoginRequest("testuser", "password123");
        User user = new User();
        user.setUsername("testuser");
        user.setPasswordHash("encodedPassword");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encodedPassword")).thenReturn(true);
        when(jwtService.generateToken("testuser")).thenReturn("jwt-token");

        // Act
        TokenResponse response = authService.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("jwt-token", response.token());
    }

    @Test
    void login_NonExistentUser_ThrowsBadCredentialsException() {
        // Arrange
        LoginRequest request = new LoginRequest("nonexistent", "password123");
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
            BadCredentialsException.class,
            () -> authService.login(request)
        );
    }

    @Test
    void login_WrongPassword_ThrowsBadCredentialsException() {
        // Arrange
        LoginRequest request = new LoginRequest("testuser", "wrongpassword");
        User user = new User();
        user.setUsername("testuser");
        user.setPasswordHash("encodedPassword");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "encodedPassword")).thenReturn(false);

        // Act & Assert
        assertThrows(
            BadCredentialsException.class,
            () -> authService.login(request)
        );
    }
}
