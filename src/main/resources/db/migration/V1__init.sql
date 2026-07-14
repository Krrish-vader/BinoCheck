-- Binocheck Initial Schema Migration
-- Note: If RAG/embeddings are added to this project later, the pgvector extension can be enabled
-- here by adding "CREATE EXTENSION IF NOT EXISTS vector;" in a future migration script.

CREATE TABLE analyzed_repos (
    id BIGSERIAL PRIMARY KEY,
    repo_url VARCHAR(255) NOT NULL UNIQUE,
    owner VARCHAR(255) NOT NULL,
    repo_name VARCHAR(255) NOT NULL,
    description TEXT,
    primary_language VARCHAR(255),
    stars_count INTEGER,
    analysis_result TEXT,
    file_tree_json TEXT,
    analyzed_at TIMESTAMP NOT NULL
);

CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    message_text TEXT NOT NULL,
    sent_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_chat_messages_repo FOREIGN KEY (repo_id) REFERENCES analyzed_repos(id) ON DELETE CASCADE
);
