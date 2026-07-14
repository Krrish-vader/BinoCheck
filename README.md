# Binocheck — GitHub Repository Architect & Tech Stack Explainer

Binocheck is a premium developer-focused web application that analyzes any public GitHub repository and provides a comprehensive explanation of its architecture, technology stack, and module structure. It also features a built-in conversational AI chatbot allowing users to ask specific follow-up questions about the repository's files and components.

---

## 🛠️ Technology Stack

- **Backend**: Java 21, Spring Boot 3.3.1 (Spring Web, WebFlux/WebClient, Spring Data JPA)
- **Database**: PostgreSQL (Persistent local storage for analysis caching and chat history)
- **Database Migrations**: Flyway (Version-controlled database schema migrations)
- **AI Integration**: Google Gemini API (`gemini-1.5-flash` model with Structured Output JSON Schema)
- **Frontend**: Semantic HTML5, Vanilla CSS3 (Custom-designed developer dark aesthetic), and Vanilla ES6 JavaScript (using Web APIs, Fetch, and dynamic DOM manipulation)
- **Containerization**: Docker & Docker Compose (Multi-stage build)

---

## 🚀 Getting Started

### Prerequisites

- **Java JDK 21** or higher
- **Apache Maven 3.6+**
- **Git**
- **Docker & Docker Compose** (highly recommended for starting PostgreSQL)

### Setup & Credentials

To run Binocheck, you need a Google Gemini API Key. A GitHub personal access token is optional but recommended to avoid GitHub API rate limits.

1. **Gemini API Key**: Obtain one from [Google AI Studio](https://aistudio.google.com/).
2. **GitHub Token**: Obtain a fine-grained or classic token from [GitHub Settings](https://github.com/settings/tokens).

---

### 💻 Local Development (Simplest Setup)

To run the application locally without installing PostgreSQL directly on your host machine:

1. **Start the database container only**:
   ```bash
   docker compose up -d postgres
   ```
   This launches a PostgreSQL container on port `5432` with a persistent named volume.

2. Set your environment variables in your terminal:
   
   **Windows (PowerShell)**:
   ```powershell
   $env:GEMINI_API_KEY="AIzaSyYourGeminiApiKeyHere"
   $env:GITHUB_TOKEN="ghp_YourGitHubTokenHere" # Optional
   ```

   **Linux / macOS**:
   ```bash
   export GEMINI_API_KEY="AIzaSyYourGeminiApiKeyHere"
   export GITHUB_TOKEN="ghp_YourGitHubTokenHere" # Optional
   ```

3. **Run the Spring Boot application**:
   ```bash
   mvn clean spring-boot:run
   ```
   *Note: Spring Boot will automatically connect to the Docker-hosted Postgres on `localhost:5432` and Flyway will apply migrations on startup.*

4. Access the web interface in your browser:
   [http://localhost:8080/](http://localhost:8080/)

---

### 🐳 Run Entirely with Docker Compose

1. Create a `.env` file in the root directory:
   ```env
   # API Keys
   GEMINI_API_KEY=AIzaSyYourGeminiApiKeyHere
   GITHUB_TOKEN=ghp_YourGitHubTokenHere
   
   # PostgreSQL Configurations
   POSTGRES_DB=binocheck
   POSTGRES_USER=postgres
   POSTGRES_PASSWORD=postgres
   ```
2. Build and launch the containerized application:
   ```bash
   docker compose up --build
   ```
   *Note: The application service includes a health check on the PostgreSQL container, waiting for it to be fully ready before starting the Spring Boot backend.*

3. Open the application at [http://localhost:8080/](http://localhost:8080/).

---

## 🔍 Features & API Documentation

### Features
- **Smart Tech Badge Extraction**: Instantly parses configuration dependencies (like `pom.xml`, `package.json`, `requirements.txt`, `go.mod`, etc.) and shows the list of detected technologies.
- **Codebase Mapping**: Generates a visually clean list of key folders and directories, explaining the purpose of each.
- **Interactive Architect Chat**: Chat with the system about the codebase. All chat history is saved and linked to the repository analysis.
- **Persistent Postgres Cache**: Analyzed repository structures and chat sessions are stored securely in PostgreSQL.

### API Endpoints
- `POST /api/analyze` - Parses and analyzes a public repository URL.
- `GET /api/repos` - Lists all previously analyzed repositories.
- `POST /api/chat` - Submits a question about a repository and returns the model reply.
- `GET /api/repos/{id}/chat` - Retrieves chat history for a specific repository.

---

## ⚙️ Performance Caps & Guardrails
To optimize speed and work cleanly within rate limits, Binocheck enforces the following limits:
- **Max Tree Depth**: `3 levels` for file hierarchy scan.
- **Max File Count**: `150 files` processed in a single run.
- **Max Individual File Read**: `50 KB` per configuration or manifest file.

---

## 🧬 Future Expansion / pgvector Compatibility
The database schema has been prepared with Flyway version control. If you decide to add RAG or semantic vector embeddings later, you can enable the `pgvector` extension in a new Flyway migration script by executing:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```
This can be applied to the existing database without requiring a schema overhaul or manual table rebuilds.
