-- Create users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Add user_id column to analyzed_repos
ALTER TABLE analyzed_repos ADD COLUMN user_id BIGINT;

-- Add foreign key constraint with cascade delete
ALTER TABLE analyzed_repos 
ADD CONSTRAINT fk_analyzed_repos_user 
FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Drop old unique constraint on repo_url
ALTER TABLE analyzed_repos DROP CONSTRAINT IF EXISTS analyzed_repos_repo_url_key;

-- Add composite unique constraint on (user_id, repo_url)
ALTER TABLE analyzed_repos
ADD CONSTRAINT unique_user_repo UNIQUE (user_id, repo_url);
