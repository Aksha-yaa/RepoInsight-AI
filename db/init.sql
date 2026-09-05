CREATE DATABASE IF NOT EXISTS repo_intelligence;
USE repo_intelligence;
CREATE TABLE IF NOT EXISTS users (
  user_id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL, email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(512) NOT NULL, demo_used BOOLEAN NOT NULL DEFAULT FALSE,
  created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS repositories (
  repository_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, repository_name VARCHAR(255) NOT NULL, repository_url VARCHAR(512) NOT NULL,
  programming_languages JSON NULL, stars BIGINT NOT NULL DEFAULT 0, forks BIGINT NOT NULL DEFAULT 0, analyzed_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_repositories_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS analysis_reports (
  report_id BIGINT AUTO_INCREMENT PRIMARY KEY, repository_id BIGINT NOT NULL, summary TEXT NOT NULL, architecture_details TEXT NOT NULL,
  technology_insights TEXT NOT NULL, recommendations TEXT NOT NULL, readme_content LONGTEXT NOT NULL,
  file_tree_json LONGTEXT NOT NULL, recent_commits_json LONGTEXT NOT NULL, fetched_files_json LONGTEXT NOT NULL,
  generated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reports_repository FOREIGN KEY (repository_id) REFERENCES repositories(repository_id) ON DELETE CASCADE
);
