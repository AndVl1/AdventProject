package com.andvl.bookshelf.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-key-for-testing-purposes-only-123456");
        jwtProperties.setTtlSeconds(3600);
        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void generateToken_ReturnsValidJwt() {
        // Act
        String token = jwtService.generateToken("testuser");

        // Assert
        assertNotNull(token);
        assertTrue(token.length() > 0);
    }

    @Test
    void extractUsername_ValidToken_ReturnsCorrectUsername() {
        // Arrange
        String token = jwtService.generateToken("testuser");

        // Act
        String username = jwtService.extractUsername(token);

        // Assert
        assertEquals("testuser", username);
    }

    @Test
    void isTokenValid_ValidToken_ReturnsTrue() {
        // Arrange
        String token = jwtService.generateToken("testuser");

        // Act
        boolean isValid = jwtService.isTokenValid(token);

        // Assert
        assertTrue(isValid);
    }

    @Test
    void isTokenValid_InvalidToken_ReturnsFalse() {
        // Arrange
        String invalidToken = "invalid.token.string";

        // Act
        boolean isValid = jwtService.isTokenValid(invalidToken);

        // Assert
        assertFalse(isValid);
    }

    @Test
    void isTokenValid_ExpiredToken_ReturnsFalse() {
        // Arrange
        JwtProperties shortTtlProps = new JwtProperties();
        shortTtlProps.setSecret("test-secret-key-for-testing-purposes-only-123456");
        shortTtlProps.setTtlSeconds(-1); // Already expired
        JwtService shortTtlService = new JwtService(shortTtlProps);
        String expiredToken = shortTtlService.generateToken("testuser");

        // Act
        boolean isValid = jwtService.isTokenValid(expiredToken);

        // Assert
        assertFalse(isValid);
    }

    @Test
    void extractUsername_ValidToken_CanExtractMultipleTimes() {
        // Arrange
        String token = jwtService.generateToken("testuser");

        // Act
        String username1 = jwtService.extractUsername(token);
        String username2 = jwtService.extractUsername(token);

        // Assert
        assertEquals(username1, username2);
        assertEquals("testuser", username1);
    }
}
