# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
./gradlew build              # Full build with tests
./gradlew build -x test      # Build skipping tests
./gradlew bootRun            # Run the application (local profile)
./gradlew test               # Run all tests
./gradlew test --tests "studying.blog.service.EnrollServiceTest"  # Single test class
./gradlew test --tests "studying.blog.service.EnrollServiceTest.testMethodName"  # Single test
```

## Required Environment Variables

The app will not start without these:

```
JWT_SECRET_KEY=<base64-encoded-secret>
DB_URL=jdbc:mysql://localhost:3306/blog
DB_USERNAME=<username>
DB_PASSWORD=<password>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=<id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=<secret>
```

Local development also requires MySQL (port 3306) and Redis (localhost:6379) running.

## Architecture Overview

This is a **lecture enrollment system** (강의 신청 시스템) — not a blog despite the package name. The core feature is fair, high-concurrency enrollment using a Redis-backed waiting queue.

### Enrollment Flow

1. User joins queue: `POST /api/lectures/{id}/queue` → stored in Redis sorted set by timestamp
2. User polls position: `GET /api/lectures/{id}/queue/me`
3. `LectureQueueScheduler` runs every 5s → grants admission tickets (batch of 50, 10-min TTL in Redis) via atomic Lua script
4. User enrolls: `POST /api/lectures/{id}/enroll`
   - Atomically claims ticket (Lua: CHECK + DEL)
   - Acquires `PESSIMISTIC_WRITE` lock on `Lecture`
   - Checks capacity/status, inserts `Enrollment`
   - On DB failure, restores admission ticket so user can retry

### Security: Two Filter Chains

- **Chain 1** (`/api/**`): Stateless JWT authentication via `TokenAuthenticationFilter`
- **Chain 2** (everything else): OAuth2 (Google) + session-based auth, Thymeleaf views

`CustomPrincipal` holds `userId + email` and is accessible in controllers via `@AuthenticationPrincipal`.

### Key Concurrency Design

- `LectureRepository.findByIdWithLock()` uses `@Lock(PESSIMISTIC_WRITE)` to prevent overselling
- Redis Lua scripts in `QueueService` ensure atomic queue operations
- Enrollment is idempotent: duplicate calls return `ALREADY_ENROLLED` (unique DB constraint on `(lecture_id, user_id)`)
- `EnrollService` and `QueueService` have extensive timing instrumentation (Redis/DB latency logging)

### Profiles

- `local`: `ddl-auto: update`, SQL logging on, Redis at localhost:6379
- `prod`: `ddl-auto: none`, schema managed manually

### Testing

- Tests use `@ActiveProfiles("test")` with H2 in-memory database
- `EnrollConcurrencyTest` uses 10 threads on a 1-seat lecture to verify the pessimistic lock
- `EnrollServiceTest` covers idempotency and DB failure/recovery scenarios
- JWT test helpers live in `config/jwt/JwtFactory.java`
