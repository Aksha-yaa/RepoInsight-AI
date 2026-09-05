# RepoInsight AI

> AI-powered GitHub repository intelligence built with Java 17, Servlets, MySQL, GitHub REST API, and Google Gemini.

RepoInsight AI turns a public GitHub repository URL into an evidence-based engineering dashboard. A Gemini analysis agent decides which repository signals it needs, calls GitHub tools one at a time, produces four distinct report sections, and stores the finished report in MySQL for later review.

## What it does

- Accepts either `owner/repository` or a full public GitHub repository URL.
- Collects repository metadata first, then lets the analysis agent request the README, file tree, individual files, and recent commits only when needed.
- Generates four independent AI report sections: summary, architecture, technology insights, and recommendations.
- Verifies a generated test claim against the fetched file tree before the report is saved.
- Displays a responsive report dashboard with repository evidence and readable saved-report history.
- Requires an account, provides one free analysis per account, and keeps each account's history private.
- Saves all four report sections plus fetched README, file-tree, and commit evidence to MySQL and loads the complete report when history is opened.

## Dashboard preview

The dashboard contains a repository overview, activity counters, AI summary, language breakdown, technology tags, and interactive analysis tabs.

```text
GitHub URL → Java Servlet → Gemini agent ⇄ GitHub tools → MySQL
    ↑                                             ↓
    └──────────── Responsive Vanilla JavaScript UI ┘
```

## Architecture

```mermaid
flowchart LR
    U[Browser: HTML, CSS, Vanilla JS] --> S[Java 17 Servlet API]
    S --> AI[Google Gemini API]
    AI --> G[GitHub REST API tools]
    S --> R[RepositoryService]
    R --> DB[(MySQL via JDBC)]
```

## Technology stack

| Layer | Technology |
| --- | --- |
| Frontend | HTML5, CSS3, Vanilla JavaScript |
| Backend | Core Java 17, Jakarta Servlets, Java HTTP Client |
| Data access | JDBC, MySQL 8, prepared statements |
| AI and APIs | Google Gemini function calling, GitHub REST API |
| Build and deployment | Maven, Docker, Docker Compose, GitHub Actions |

## Project structure

```text
src/main/java/com/repointel/
├── controller/     # HTTP servlet endpoints
├── model/          # Repository and report data models
├── service/        # GitHub, Gemini, JDBC, and report services
└── util/           # Configuration and JSON helpers
src/main/webapp/    # Dashboard HTML, CSS, and JavaScript
db/init.sql         # MySQL tables and relationships
.github/workflows/  # Docker startup verification workflow
Dockerfile          # Tomcat deployment image
docker-compose.yml  # Application and MySQL services
```

## API endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/repositories/analyze` | Fetches GitHub data for `{ "repositoryUrl": "owner/repository" }` |
| `POST` | `/api/insights/generate` | Runs the Gemini tool-using agent and saves the four-section report |
| `GET` | `/api/reports/` | Lists saved reports |
| `GET` | `/api/reports/{id}` | Retrieves one saved report |
| `POST` | `/api/auth/register` | Creates an account and starts a session |
| `POST` | `/api/auth/login` | Starts an account session |
| `POST` | `/api/auth/logout` | Ends the current session |
| `GET` | `/api/auth` | Returns the current session account |

## Run with Docker

### 1. Configure keys

Copy `.env.example` to `.env` and set your values:

```dotenv
GEMINI_API_KEY=your_google_gemini_api_key
GITHUB_TOKEN=optional_github_personal_access_token
DB_PASSWORD=repo_password
MYSQL_ROOT_PASSWORD=change_this_for_production
```

`GEMINI_API_KEY` is required to generate AI analysis. `GITHUB_TOKEN` is optional but recommended because it increases GitHub API rate limits.
The default Gemini model is `gemini-2.5-flash`; set `GEMINI_MODEL` explicitly if your API project uses another currently supported model.

### 2. Start the application

```bash
docker compose up --build
```

Open `http://localhost:8080` and try:

```text
https://github.com/octocat/Hello-World
```

Docker Compose starts Tomcat with the application and a MySQL 8 database. The database uses the `mysql_data` named volume, so saved reports survive container restarts.

## Verify from GitHub

This repository includes an automated GitHub Actions workflow, **Verify RepoInsight AI**. Every push to `main`:

1. Builds the Docker image.
2. Starts MySQL and the Java application with Docker Compose.
3. Waits for the application to respond on port 8080.
4. Stops the test containers.

Open the repository's **Actions** tab after uploading the project. A green check confirms that the Dockerized application successfully builds and starts. The workflow uses a fake Gemini key; it does not perform a paid AI request or expose your real secret.

### Verify the real AI integration without installing Docker

1. In the GitHub repository, open **Settings** → **Secrets and variables** → **Actions**.
2. Click **New repository secret**.
3. Name it `GEMINI_API_KEY` and paste your key. GitHub encrypts it and does not show it again.
4. Open the **Actions** tab → **Verify RepoInsight AI** → **Run workflow** → **Run workflow**.

This manual run starts Docker on GitHub's hosted runner, analyzes `octocat/Hello-World`, calls Gemini, saves the report to MySQL, and checks that a summary was returned. Your computer does not download or run Docker. The key is not printed in logs or committed to the repository.

## Gemini analysis flow

The agent receives the repository identifier and these functions:

- `getFileTree` — fetches the repository file and folder structure.
- `getFileContents` — fetches one repository-relative file.
- `getReadme` — fetches the README when it is useful.
- `getRecentCommits` — fetches a requested number of recent commit messages.
- `generateReport` — ends the loop and returns the four report sections.

The loop is limited to eight turns. GitHub tool failures are returned to Gemini as function responses containing an error, allowing the agent to adjust its investigation. If the agent does not call `generateReport`, the application saves a clearly marked partial report instead of failing with an unhandled exception.

## Accounts, demo access, and report history

Accounts use server-side HTTP sessions and PBKDF2-HMAC-SHA256 password hashes. Each account receives one free analysis. The database atomically marks that demo as used before the agent starts, which prevents duplicate concurrent free analyses but means a failed analysis attempt also consumes the demo. This is an intentional abuse-control tradeoff; paid usage or retries should be added later with billing or an administrator reset flow.

Every saved repository belongs to the authenticated account. Both the history list and report-detail query filter by that account, so knowing another report ID does not grant access to it. Each report stores summary, architecture details, technology insights, recommendations, generation time, README content, file-tree paths, recent commit messages, and bounded excerpts of individual source files fetched by the agent. Selecting **Open full report** opens the complete saved evidence.

## Database schema

The MySQL schema is initialized from `db/init.sql`.

- `users` — authenticated accounts, PBKDF2 password hashes, and one-time demo state.
- `repositories` — GitHub URL, language data, stars, forks, and analysis time.
- `analysis_reports` — Gemini report sections, fetched README, file tree, recent commits, bounded source excerpts, and generation time.

The application uses JDBC prepared statements and a transaction when saving a repository/report pair.

### Existing database volumes

The new account schema is applied automatically only when MySQL initializes a new database volume. If upgrading an existing deployment created by an earlier version, back up the database and recreate the development volume or run a reviewed migration before deploying. Existing anonymous reports cannot be safely assigned to a user automatically.

## Security notes

- Never commit `.env`, API keys, passwords, or access tokens.
- Keep `GEMINI_API_KEY` in encrypted host or GitHub secrets for deployed environments.
- Use a GitHub token with only the permissions you need.
- Serve production traffic over HTTPS and configure the session cookie as secure at the TLS-terminating deployment edge; the local Docker smoke test uses HTTP.
- Account registration limits one analysis per account, but it is not a payment or identity-verification system. Add email verification, rate limiting, CAPTCHA, or billing before treating the demo limit as a commercial entitlement.
- This application analyzes public repositories only.


## Current verification status

- Frontend files have passed the available editor diagnostics; run the Docker workflow for a full containerized verification.
- The GitHub Actions workflow verifies Docker build and application startup after upload.
- A full AI result requires a valid `GEMINI_API_KEY` at runtime; test it by analyzing a public repository after deployment.

