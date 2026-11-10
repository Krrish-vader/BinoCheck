# BinoCheck

Analyzes public GitHub repositories with Google Gemini. Users sign up, paste a repo URL and get a tech stack, architecture summary, module map and a chat about the code. Deployed on Render with Neon Postgres.

## Stack
- Java 21, Spring Boot 3.3 (Web MVC). WebFlux is used only for `WebClient`, and every call ends in `.block()`.
- PostgreSQL with Flyway migrations. Hibernate runs with `ddl-auto: validate`, so it never creates or alters tables.
- Session-cookie auth: `HttpSession` + `AuthInterceptor` on `/api/**`. There is no Spring Security filter chain; only `spring-security-crypto` is used, for BCrypt.
- Frontend: plain HTML/CSS/JS in `src/main/resources/static/`, with no build step and no framework.

## Layout
- `config/WebClientConfig`: the GitHub and Gemini `WebClient`s, with connect and response timeouts (`app.*` in `application.yml`).
- `controller/`: `AuthController` (`/api/auth/*`) and `AnalysisController` (`/api/analyze`, `/api/repos`, `/api/chat`).
- `service/GitHubService`: URL parsing, repo metadata, file tree (depth ≤3, max 150 entries), manifest files (fetched in parallel).
- `service/GeminiService`: builds prompts and calls the `generateContent` REST API. Analysis uses a structured JSON response schema. Chat `history` must exclude the current question; the service appends it.
- `security/`: `RateLimiter` (in-memory, single instance) and `SecurityHeadersFilter` (CSP and related headers).
- `exception/GlobalExceptionHandler`: the only place errors become HTTP responses.
- `db/migration/`: Flyway scripts `V1__init.sql`, `V2__add_users.sql`, ...

## Commands
- Run tests: `mvn test`
  - `BinocheckApplicationTests` uses Testcontainers and is **skipped when Docker isn't running**.
  - All other tests run without a database.
- Run locally (Postgres in Docker on host port **5433**, not 5432):
  1. `docker compose up -d postgres`
  2. set `GEMINI_API_KEY`
  3. `mvn spring-boot:run`
  4. open http://localhost:8080
- Full stack in Docker: `docker compose up --build` (reads `.env`).
- Environment variables:
  - Required: `GEMINI_API_KEY`
  - Optional: `GITHUB_TOKEN`, `GEMINI_MODEL`, `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` (or `SPRING_DATASOURCE_*`), `COOKIE_SECURE`
  - `.env` is gitignored. Never commit keys, and never print their values.

## Security rules (keep these when changing code)
- **Never put untrusted text into `innerHTML`.** Gemini output, repo names, descriptions and server error messages all count as untrusted, because a repo's README can steer what Gemini returns.
  - Use `textContent`, or wrap values in `escapeHtml()` in `app.js`.
  - Links must pass `safeUrl()` (http/https only).
- CSP is `script-src 'self'`, so don't add inline `<script>` blocks or `onclick=` attributes. Attach handlers in `app.js`.
- **Load per-user data through `findOwnedRepo(id, user)`.** Another user's data must return 404, never 403, and never the data itself.
- **Every endpoint that calls Gemini must call `rateLimiter.check(...)`.** Each call costs money.
- **Validate request DTOs** with `@Valid` and Jakarta constraints.
- **Throw domain exceptions** (`ResourceNotFoundException`, `InvalidGitHubUrlException`, ...). Never return raw exception messages, SQL text or upstream API bodies to the client; log them instead.
- Don't pass GitHub's own HTTP status through. The frontend treats a 401 as "logged out", so a 401 from GitHub would log the user out.
- Store repo URLs only in canonical form: `gitHubService.canonicalUrl(gitHubService.parseUrl(url))`.
- **Login must keep `request.changeSessionId()`** (protects against session fixation).
- Don't add `@CrossOrigin`. The frontend is same-origin.

## Database changes
- Add a new `V<n>__description.sql` migration. **Never edit a migration that has already been applied.** Production (Neon) already ran V1 and V2.
- Keep entities matching the schema; the `validate` setting fails startup on any mismatch.
- Ask before any change that drops or rewrites data.

## Testing conventions
- Controllers: `@WebMvcTest` + `@Import({WebMvcConfig.class, AuthInterceptor.class, RateLimiter.class})`, with repositories as `@MockBean`. Authenticate by setting `userId` on a `MockHttpSession`.
- The `RateLimiter` bean is shared across tests in one context. Give tests that exhaust a limit their own key, for example a distinct `remoteAddr`.
- Add a test for every bug fix, and run `mvn test` before saying work is done.

## Workflow
- Branches: `fix/...` or `feat/...`, never commit directly to `main`.
- Commit messages: Conventional Commits (`fix: ...`, `feat: ...`), matching the existing history.
- The user pushes to GitHub themselves. Don't push without being asked.
- For UI changes, also check the page in a browser.

## Known issues (not yet fixed)
- The tree sampler takes the first 150 paths at depth ≤3 in alphabetical order, so large repos fill up with `.github/` and asset folders before `src/`. The Trees API `truncated` flag is ignored.
- No size limits on prompts: the whole README is sent, and every chat request sends the full analysis JSON, file tree and all earlier messages.
- The analysis cache never expires (no commit SHA check).
- The Dockerfile runs as root and has no `HEALTHCHECK`.
- No CI workflow (`.github/workflows`) yet.
- The mobile layout (`@media (max-width: 900px)` at the end of `styles.css`) was checked with static page previews, not yet on a real phone against the live app.
