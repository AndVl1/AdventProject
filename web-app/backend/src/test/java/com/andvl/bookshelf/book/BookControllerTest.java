package com.andvl.bookshelf.book;

import com.andvl.bookshelf.auth.AuthService;
import com.andvl.bookshelf.auth.dto.LoginRequest;
import com.andvl.bookshelf.auth.dto.RegisterRequest;
import com.andvl.bookshelf.auth.dto.TokenResponse;
import com.andvl.bookshelf.book.dto.CreateBookRequest;
import com.andvl.bookshelf.book.dto.UpdateStatusRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    private String getAuthToken() {
        // Generate unique username to avoid conflicts
        String uniqueUsername = "bookuser_" + System.currentTimeMillis();

        // Register and login to get auth token
        RegisterRequest registerRequest = new RegisterRequest(uniqueUsername, "password123");
        authService.register(registerRequest);

        LoginRequest loginRequest = new LoginRequest(uniqueUsername, "password123");
        TokenResponse tokenResponse = authService.login(loginRequest);
        return tokenResponse.token();
    }

    @Test
    void getBooks_WithValidToken_ReturnsBooks() throws Exception {
        mockMvc.perform(get("/api/books")
                .header("Authorization", "Bearer " + getAuthToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getBooks_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createBook_ValidRequest_ReturnsCreated() throws Exception {
        CreateBookRequest request = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "WANT_TO_READ"
        );

        mockMvc.perform(post("/api/books")
                .header("Authorization", "Bearer " + getAuthToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Book"))
                .andExpect(jsonPath("$.author").value("Test Author"))
                .andExpect(jsonPath("$.status").value("WANT_TO_READ"));
    }

    @Test
    void createBook_InvalidRequest_Returns400() throws Exception {
        String invalidRequest = "{\"title\":\"\"}";

        mockMvc.perform(post("/api/books")
                .header("Authorization", "Bearer " + getAuthToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBook_InvalidStatus_Returns400() throws Exception {
        CreateBookRequest request = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "INVALID_STATUS"
        );

        mockMvc.perform(post("/api/books")
                .header("Authorization", "Bearer " + getAuthToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStatus_ValidRequest_ReturnsUpdatedBook() throws Exception {
        // Arrange - use same token for both operations
        String authToken = getAuthToken();
        CreateBookRequest createRequest = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "WANT_TO_READ"
        );

        String response = mockMvc.perform(post("/api/books")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract book ID from response
        Long bookId = objectMapper.readTree(response).get("id").asLong();

        // Now update status
        UpdateStatusRequest updateRequest = new UpdateStatusRequest("READING");

        mockMvc.perform(patch("/api/books/" + bookId + "/status")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READING"));
    }

    @Test
    void updateStatus_NonExistentBook_Returns404() throws Exception {
        UpdateStatusRequest request = new UpdateStatusRequest("READING");

        mockMvc.perform(patch("/api/books/99999/status")
                .header("Authorization", "Bearer " + getAuthToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteBook_ValidRequest_Returns204() throws Exception {
        // Arrange - use same token for both operations
        String authToken = getAuthToken();
        CreateBookRequest createRequest = new CreateBookRequest(
            "Test Book",
            "Test Author",
            "WANT_TO_READ"
        );

        String response = mockMvc.perform(post("/api/books")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long bookId = objectMapper.readTree(response).get("id").asLong();

        // Now delete it
        mockMvc.perform(delete("/api/books/" + bookId)
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteBook_NonExistentBook_Returns404() throws Exception {
        mockMvc.perform(delete("/api/books/99999")
                .header("Authorization", "Bearer " + getAuthToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStats_WithValidToken_ReturnsStats() throws Exception {
        mockMvc.perform(get("/api/books/stats")
                .header("Authorization", "Bearer " + getAuthToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wantToRead").exists())
                .andExpect(jsonPath("$.reading").exists())
                .andExpect(jsonPath("$.read").exists())
                .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void getStats_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/books/stats"))
                .andExpect(status().isUnauthorized());
    }
}
