# Bookshelf Backend — 2026-04-16

## Что сделано

Реализован Spring Boot 3.3.5 / Java 21 / Maven backend для приложения Bookshelf в директории `web-app/backend/`.

**Слои:**
- `user/` — сущность `User` (`@Table("users")`, unique username) + `UserRepository`
- `book/` — сущность `Book` (enum `BookStatus` STRING, `@ManyToOne(LAZY)` owner, индекс на owner_id), `BookRepository` (4 query-метода по блюпринту), `BookService`, `BookController`
- `auth/` — `AuthService` (register/login), `AuthController`, DTO (RegisterRequest, LoginRequest, TokenResponse)
- `security/` — `JwtService` (HS256, jjwt 0.12.6), `JwtAuthenticationFilter`, `CustomUserDetailsService`, `JwtProperties`
- `config/` — `SecurityConfig` (STATELESS, csrf off, EntryPoint→JSON 401), `CorsConfig` (origin 5173), `PasswordEncoderConfig` (BCrypt)
- `common/` — `ApiExceptionHandler`, `NotFoundException`, `ConflictException`, `ValidationException`

**API:** POST /api/auth/register, POST /api/auth/login, GET/POST /api/books, PATCH /api/books/{id}/status, DELETE /api/books/{id}, GET /api/books/stats. Все эндпоинты кроме /api/auth/** требуют JWT.

## Как запустить

```bash
cd web-app/backend
./mvnw spring-boot:run
# Сервер поднимается на http://localhost:8080

# Регистрация:
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'
# → {"token":"<jwt>"}
```

Сборка без тестов: `./mvnw clean package -DskipTests`

## Известные ограничения

- БД — H2 in-memory (create-drop), данные сбрасываются при перезапуске. Для продакшена нужна замена на PostgreSQL.
- Maven wrapper скачивает Maven 3.9.9 при первом запуске (нужен интернет).
- Тесты не написаны — запланированы в следующей сессии.
