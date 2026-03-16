# Jira Shift Roster Auto-Assign

A lightweight Spring Boot application that automatically manages Jira ticket assignments based on a monthly shift roster. Upload an Excel schedule once per month — the app assigns unassigned Jira tickets to whoever is currently on shift and unassigns them when the shift ends.

https://jiraautoassignshiftroster-production.up.railway.app/

---

## Features

- **Excel Upload with Preview** — upload a shift roster, review parsed data before saving to database
- **Auto Assign** — unassigned Jira tickets are assigned to the active shift person every minute
- **Auto Unassign** — when a shift ends, tickets are unassigned so the next shift person picks them up
- **Date Range Expansion** — one Excel row covering a date range creates individual daily entries automatically
- **Header Validation** — wrong Excel format is detected and blocked before any data is saved
- **No File Storage** — Excel is read in memory and discarded immediately after parsing
- **Lightweight Frontend** — single HTML page, no framework, fast load

---

## How It Works

### 1. Prepare Excel
Create an `.xlsx` or `.xls` file with these exact column headers (case-insensitive):

| Email | Shift Start | Shift End | From Date | To Date |
|---|---|---|---|---|
| alice@company.com | 07:00 | 16:00 | 01/03/2026 | 31/03/2026 |
| bob@company.com | 15:30 | 00:30 | 01/03/2026 | 15/03/2026 |
| bob@company.com | 07:00 | 16:00 | 16/03/2026 | 31/03/2026 |
| charlie@company.com | 22:30 | 07:30 | 01/03/2026 | 31/03/2026 |

- **Multiple people on same date** — add multiple rows with overlapping date ranges
- **Person changes shift mid-month** — add two rows for the same email with different date ranges
- **Date formats accepted** — `dd/MM/yyyy`, `MM/dd/yyyy`, `yyyy-MM-dd`, or native Excel date cells
- **Time formats accepted** — `HH:mm`, `H:mm`, `HH:mm:ss`, or native Excel time cells

### 2. Upload via Web UI
1. Open the app URL
2. Select or drag & drop the Excel file
3. Click **Preview** — review the parsed rows and total entries without touching the database
4. Click **Confirm & Save** — data is saved to PostgreSQL

### 3. Scheduler Runs Every Minute
- Finds who is currently on shift from the database
- Assigns all unassigned Jira tickets (matching your JQL) to the active shift person
- When a shift ends, unassigns tickets from the outgoing person so the incoming shift picks them up

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4 |
| Database | PostgreSQL |
| Excel Parsing | Apache POI 5.2.5 |
| Frontend | Vanilla HTML / CSS / JS |
| Scheduler | Spring `@Scheduled` (every minute) |
| Deployment | Docker (Alpine JRE), Railway.app |

---

## Project Structure

```
src/main/
├── java/com/jira/autoassign/
│   ├── JiraAutoAssignApplication.java   # entry point
│   ├── client/
│   │   └── JiraClient.java              # Jira REST API calls
│   ├── config/
│   │   ├── AppConfig.java               # Spring beans
│   │   └── JiraProperties.java          # jira.* config properties
│   ├── controller/
│   │   └── UploadController.java        # REST endpoints
│   ├── entity/
│   │   └── ShiftRoster.java             # DB table definition
│   ├── repository/
│   │   └── ShiftRosterRepository.java   # DB queries
│   ├── scheduler/
│   │   └── AssignScheduler.java         # cron trigger
│   └── service/
│       ├── ExcelService.java            # Excel parsing + DB save
│       └── ShiftAssignService.java      # assign/unassign logic
└── resources/
    ├── application.properties           # config (env vars)
    └── static/
        └── index.html                   # frontend
```

---

## REST API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/preview` | Parse Excel, return rows for review — nothing saved |
| `POST` | `/api/upload` | Parse Excel, expand date ranges, save to DB |
| `GET` | `/api/status` | Current active shift and assignees |
| `GET` | `/api/schedule` | This month's full schedule |

---

## Deployment — Railway.app

### Prerequisites
- [Railway account](https://railway.app)
- GitHub repository with this code
- Jira Cloud account with API token

### Steps

**1. Push to GitHub**
```bash
git init
git add .
git commit -m "initial commit"
git remote add origin https://github.com/your-username/your-repo.git
git push -u origin main
```

**2. Create Railway Project**
- Go to [railway.app](https://railway.app) → New Project → Deploy from GitHub repo
- Select your repository

**3. Add PostgreSQL**
- Inside your Railway project → New Service → Database → PostgreSQL
- Railway automatically sets `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`

**4. Set Environment Variables**

In Railway → your app service → Variables, add:

| Variable | Value |
|---|---|
| `JIRA_URL` | `https://yourcompany.atlassian.net` |
| `JIRA_EMAIL` | `your-email@company.com` |
| `JIRA_API_TOKEN` | your Jira API token |
| `JIRA_CUSTOM_JQL` | your JQL query (must include `Assignee in (EMPTY)`) |

> **How to get a Jira API token:** Jira → Profile → Security → Create and manage API tokens

**5. Deploy**
- Railway builds the Docker image and deploys automatically
- Database tables are created on first boot (`spring.jpa.hibernate.ddl-auto=update`)
- App URL is available under Settings → Domains

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|---|---|---|---|
| `JIRA_URL` | Yes | — | Jira instance URL |
| `JIRA_EMAIL` | Yes | — | Jira login email |
| `JIRA_API_TOKEN` | Yes | — | Jira API token |
| `JIRA_CUSTOM_JQL` | Yes | — | JQL to find unassigned tickets |
| `JIRA_DRY_RUN` | No | `false` | If `true`, logs assignments without calling Jira API |
| `JIRA_SCHEDULE_CRON` | No | `0 */1 * * * *` | Cron expression for scheduler |
| `PGHOST` | Yes | `localhost` | PostgreSQL host (auto-set by Railway) |
| `PGPORT` | Yes | `5432` | PostgreSQL port (auto-set by Railway) |
| `PGUSER` | Yes | `postgres` | PostgreSQL user (auto-set by Railway) |
| `PGPASSWORD` | Yes | — | PostgreSQL password (auto-set by Railway) |
| `PGDATABASE` | Yes | `jira_roster` | PostgreSQL database name (auto-set by Railway) |
| `PORT` | No | `8080` | HTTP server port (auto-set by Railway) |

---

## Database Schema

```sql
CREATE TABLE shift_roster (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    shift_date  DATE         NOT NULL,
    shift_start TIME         NOT NULL,
    shift_end   TIME         NOT NULL,
    created_at  TIMESTAMP
);

CREATE INDEX idx_email      ON shift_roster (email);
CREATE INDEX idx_shift_date ON shift_roster (shift_date);
```

---

## Local Development

**Prerequisites:** Java 21, Maven, PostgreSQL running locally

```bash
# Clone
git clone https://github.com/your-username/your-repo.git
cd your-repo

# Set environment variables
export JIRA_URL=https://yourcompany.atlassian.net
export JIRA_EMAIL=your-email@company.com
export JIRA_API_TOKEN=your-token
export JIRA_CUSTOM_JQL="project = SAC AND status in ('In Progress') AND Assignee in (EMPTY)"
export PGHOST=localhost
export PGDATABASE=jira_roster

# Run
mvn spring-boot:run
```

Open `http://localhost:8080`

---

## Docker

```bash
# Build
docker build -t jira-shift-roster .

# Run
docker run -p 8080:8080 \
  -e JIRA_URL=https://yourcompany.atlassian.net \
  -e JIRA_EMAIL=your-email@company.com \
  -e JIRA_API_TOKEN=your-token \
  -e JIRA_CUSTOM_JQL="your JQL" \
  -e PGHOST=host.docker.internal \
  -e PGDATABASE=jira_roster \
  jira-shift-roster
```

---

## License

MIT
