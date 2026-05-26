# Jira Shift Roster Manager

A Spring Boot web application that automatically manages Jira ticket assignments based on a monthly shift roster **and** provides a live SLA breach tracker with a calendar-driven resolved-breach view.

---

## Features

### Auto-Assignment
- **Multi-Team Support** — manage Order Fallout, SAC, and any number of additional teams independently; each team has its own JQL, roster, dry-run toggle, and round-robin state
- **Parallel Scheduler** — all teams run simultaneously every minute, thread-safe
- **Excel Upload with Preview** — upload a shift roster, review parsed data before saving
- **Round-Robin Auto Assign** — unassigned Jira tickets assigned to active shift members every minute
- **Off-Shift Sweep** — when a shift ends, tickets held by the outgoing person are redistributed
- **Escalated Ticket Restore** — escalated unassigned tickets restored to their last historical assignee
- **Per-Team Dry-Run** — test mode per team; logs actions without touching Jira
- **Pause / Resume** — skip a specific person during assignment without removing them from the roster
- **Inline JQL Editor** — view and edit each team's JQL from the UI
- **Activity Log** — per-team audit trail of the last 100 assignments
- **Overnight Shift Support** — shifts crossing midnight handled correctly
- **Auto-Transition** — ticket status auto-moved to _In Progress_ on assignment

### SLA Tracker
- **Open Breached Tab** — all currently open tickets where Jira's SLA has been breached (`cf[X] = breached()`)
- **Resolved Breached Tab** — resolved/closed/cancelled tickets that breached SLA on a selected date
- **Inline Calendar** — click any past date to instantly fetch that day's resolved breaches; defaults to today
- **Breach Attribution** — for open tickets, changelog is consulted to attribute the breach to who held the ticket at the breach time (not just current assignee)
- **Severity Tagging** — severity labels fetched live from Jira with dynamic colour coding
- **Breach Reason Comments** — select a pre-configured reason and post it as a Jira comment directly from the dashboard
- **Excel Download** — download the full breach report (open + resolved) as `.xlsx`
- **Assignee Filter** — client-side filter by person across all cards
- **Professional UI** — Tailwind CSS + custom CSS, CSS dot indicators (no emoji), SVG icons

### Configuration UI
- **Jira Settings** — configure Jira email and API token from the browser (no server restart needed)
- **SLA Field ID** — configure the `customfield_XXXXX` for Time to Resolution once from Admin tab
- **Breach Reason Admin** — add/remove breach reason options shown in the SLA dropdown

---

## How It Works

### 1. Prepare the Shift Excel

Create an `.xlsx` or `.xls` file with these columns (case-insensitive, any order):

| Email | Shift Start | Shift End | From Date | To Date |
|---|---|---|---|---|
| alice@company.com | 07:00 | 16:00 | 01/03/2026 | 31/03/2026 |
| bob@company.com | 15:30 | 00:30 | 01/03/2026 | 15/03/2026 |
| charlie@company.com | 22:30 | 07:30 | 01/03/2026 | 31/03/2026 |

- **Date formats** — `dd/MM/yyyy`, `MM/dd/yyyy`, `yyyy-MM-dd`, or native Excel date cells
- **Time formats** — `HH:mm`, `H:mm`, or native Excel time cells
- **Re-uploading** replaces only the months present in the new file for that team

### 2. Upload via Web UI

1. Open the app, select the team tab
2. Drag & drop or click to select the Excel file
3. **Preview** — review parsed rows without saving
4. **Confirm & Save** — data is written to the database

### 3. Scheduler Runs Every Minute

For each team in parallel:
1. Finds who is currently on shift
2. Assigns all unassigned tickets round-robin to active shift members
3. Restores escalated + unassigned tickets to their last historical assignee
4. Sweeps tickets held by off-shift people and redistributes them

### 4. SLA Tracker

1. Click **SLA Tracker** button in the top bar
2. **Open tab** — shows all open tickets with a breached SLA, grouped by the person who held the ticket at breach time
3. **Resolved tab** — shows a calendar; click any date to load resolved breaches for that day
4. Select a breach reason from the dropdown and click **Comment** to post it to the Jira ticket

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4 |
| Database | H2 file-mode (default) / PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Excel Parsing | Apache POI 5.2.5 |
| Frontend | Vanilla HTML + CSS + JS, Tailwind CSS CDN |
| Scheduler | Spring `@Scheduled` (every minute, parallel per team) |
| Excel Export | SheetJS (xlsx 0.18.5) — client-side |
| Jira API | REST API v3 (Cloud) |

---

## Project Structure

```
src/main/
├── java/com/jira/autoassign/
│   ├── JiraAutoAssignApplication.java
│   ├── client/
│   │   └── JiraClient.java                  # All Jira REST API calls
│   ├── config/
│   │   ├── AppConfig.java                   # Spring beans (RestTemplate, etc.)
│   │   ├── DataInitializer.java             # Seeds default teams on first boot
│   │   └── JiraProperties.java              # jira.* config properties
│   ├── controller/
│   │   ├── AssignController.java            # Manual trigger endpoint
│   │   ├── BreachReasonController.java      # Breach reason CRUD
│   │   ├── SlaController.java               # SLA tracker endpoints
│   │   └── UploadController.java            # Roster, teams, settings endpoints
│   ├── entity/
│   │   ├── AssignmentLog.java
│   │   ├── BreachReason.java
│   │   ├── JiraConfig.java                  # Jira credentials + SLA field ID
│   │   ├── ShiftRoster.java
│   │   └── Team.java
│   ├── repository/
│   │   ├── AssignmentLogRepository.java
│   │   ├── BreachReasonRepository.java
│   │   ├── JiraConfigRepository.java
│   │   ├── ShiftRosterRepository.java
│   │   └── TeamRepository.java
│   ├── scheduler/
│   │   └── AssignScheduler.java             # Cron trigger (every minute)
│   └── service/
│       ├── ExcelService.java                # Excel parsing + save
│       ├── JiraConfigService.java           # Live Jira credentials management
│       └── ShiftAssignService.java          # Assign / unassign logic
└── resources/
    ├── application.properties
    ├── application-h2.properties
    ├── application-postgres.properties
    └── static/
        ├── index.html                       # Main UI (roster + SLA tracker + admin)
        └── activity.html                    # Assignment activity log
```

---

## Database

Tables are created automatically on first boot — no SQL needed.

| Table | Description |
|---|---|
| `teams` | Team config — id, name, JQL, dry_run flag |
| `shift_roster` | Expanded daily shift entries per team |
| `assignment_log` | Audit trail of every ticket assignment |
| `jira_config` | Jira email, API token, SLA field ID (single-row) |
| `breach_reason` | Admin-managed list of breach reason labels |

Two default teams are seeded on first startup:

| ID | Name | JQL basis |
|---|---|---|
| `orderfallout` | Order Fallout | `Reporting Area = "Order Fallout"` |
| `sac` | SAC Team | `Reporting Area != "Order Fallout"` |

---

## Database Profiles

### Local — H2 (default, no install needed)

```bash
./mvnw spring-boot:run
# or
java -jar target/*.jar --spring.profiles.active=h2
```

- Data stored in `./data/jiraassign.mv.db` (persists across restarts)
- H2 console: `http://localhost:8080/h2-console`
  - JDBC URL: `jdbc:h2:file:./data/jiraassign`
  - Username: `sa` / Password: *(blank)*

### Production — PostgreSQL

```bash
java -jar target/*.jar --spring.profiles.active=postgres
```

One-time DB setup:
```bash
sudo -u postgres psql -c "CREATE DATABASE jiraassign;"
sudo -u postgres psql -c "CREATE USER jiraassign WITH PASSWORD 'changeme';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE jiraassign TO jiraassign;"
```

---

## REST API

### Teams

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/teams` | List all teams |
| `POST` | `/api/teams` | Add team `{name, jql}` |
| `PUT` | `/api/teams/{id}` | Update team name or JQL |
| `DELETE` | `/api/teams/{id}` | Delete a team |

### Roster & Status

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/preview` | Parse Excel — nothing saved |
| `POST` | `/api/upload?team={id}` | Parse, expand, and save roster |
| `GET` | `/api/status?team={id}` | Current active shift members |
| `GET` | `/api/schedule?team={id}` | This month's full schedule |
| `GET` | `/api/activity` | Last 100 assignment log entries |

### Control

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/config?team={id}` | Get dry-run state |
| `POST` | `/api/config/dry-run` | Set dry-run `{teamId, dryRun: true/false}` |
| `POST` | `/api/shift/pause` | Pause assignee `{teamId, email}` |
| `POST` | `/api/shift/resume` | Resume assignee `{teamId, email}` |
| `POST` | `/api/assign/run` | Manually trigger assignment for all teams |

### SLA Tracker

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/sla?team={id}` | Open + resolved breaches (resolved = today) |
| `GET` | `/api/sla?team={id}&date=YYYY-MM-DD` | Resolved breaches for a specific date |
| `GET` | `/api/sla/config` | Get saved SLA field ID and Jira URL |
| `POST` | `/api/sla/config` | Save SLA field ID `{slaFieldId}` |
| `POST` | `/api/sla/comment` | Post breach reason `{issueKey, reason}` |
| `GET` | `/api/sla/severity-options` | Severity option labels in Jira order |

### Settings & Breach Reasons

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/settings` | Get current Jira email |
| `POST` | `/api/settings` | Save Jira email + API token `{email, token}` |
| `GET` | `/api/breach-reasons` | List all breach reason options |
| `POST` | `/api/breach-reasons` | Add a reason `{label}` |
| `DELETE` | `/api/breach-reasons/{id}` | Remove a reason |

---

## SLA Tracker — How It Works

### Breach Detection
Both open and resolved queries use Jira's own `cf[X] = breached()` JQL function — Jira is the source of truth, no Java-side re-parsing.

```
-- Open:
<team JQL> AND cf[10031] = breached() ORDER BY created DESC

-- Resolved (today):
<team JQL> AND status in ("Resolved","Closed","Cancelled")
           AND cf[10031] = breached()
           AND updated >= startOfDay() AND updated <= endOfDay()

-- Resolved (specific date):
<team JQL> AND status in ("Resolved","Closed","Cancelled")
           AND cf[10031] = breached()
           AND updated >= "2025-05-15" AND updated < "2025-05-16"
```

`updated` is used (not `resolved`) so CLOSED tickets without a `resolutiondate` are included.

### Breach Attribution (Open only)
For open tickets, the changelog API is used to find who held the ticket at the exact breach timestamp — attribution is based on that person, not the current assignee. Resolved tickets skip attribution (would require 1000+ API calls).

### SLA Field Setup
1. Go to **Admin → SLA Configuration**
2. Call `/rest/api/3/field` on your Jira instance, find `customfield_XXXXX` for _Time to Resolution_
3. Enter it and click **Save** — takes effect immediately, no restart needed

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JIRA_URL` | Yes | — | Jira instance base URL |
| `JIRA_EMAIL` | Yes* | — | Jira login email |
| `JIRA_API_TOKEN` | Yes* | — | Jira API token |
| `JIRA_CUSTOM_JQL` | Yes | — | JQL for Order Fallout team |
| `JIRA_DRY_RUN` | No | `false` | Global dry-run default on first boot |
| `JIRA_SCHEDULE_CRON` | No | `0 */1 * * * *` | Assignment cron expression |
| `DB_PROFILE` | No | `h2` | `h2` or `postgres` |
| `DB_URL` | No | H2 file path | PostgreSQL JDBC URL |
| `DB_USER` | No | `jiraassign` | DB username |
| `DB_PASSWORD` | No | `changeme` | DB password |
| `PORT` | No | `8080` | HTTP port |

*\* Can also be configured at runtime via **Jira Settings** in the browser — DB value overrides env on next request.*

> **Get a Jira API token:** Jira → Profile → Security → Create and manage API tokens

---

## Local Development

**Prerequisites:** Java 21, Maven

```bash
git clone https://github.com/gowthamkrishnagk/jiraAutoAssignShiftRoster.git
cd jiraAutoAssignShiftRoster

# Set Jira credentials (or configure via UI after starting)
export JIRA_URL=https://yourcompany.atlassian.net
export JIRA_EMAIL=your-email@company.com
export JIRA_API_TOKEN=your-token
export JIRA_CUSTOM_JQL="project = SAC AND status = 'In Progress'"

# Run (H2 is default — no DB install needed)
./mvnw spring-boot:run
```

Open `http://localhost:8080`

**Hot reload** — changes to `src/main/resources/static/` (HTML/JS/CSS) are picked up instantly. For Java changes:
```bash
./mvnw compile   # triggers DevTools restart
```

---

## Server Deployment (Ubuntu — H2 mode)

```bash
# On server: pull latest and rebuild
git pull origin main
./mvnw clean package -DskipTests

# Stop old instance
pkill -f 'jiraAutoAssign'

# Start in background (H2 file-mode — data persists in ./data/)
setsid java -jar target/jiraAutoAssignShiftRoster-*.jar \
  --spring.profiles.active=h2 > app.log 2>&1 &

# Tail logs
tail -f app.log
```

App runs on port **8080** at `http://10.169.101.69:8080`

### Port Reference

| App | Port |
|---|---|
| soql-monitor | 9002 |
| Fallout App | 8001 |
| SAC Dashboard | 8002 |
| **Jira Shift Roster Manager** | **8080** |

---

## Docker

```bash
# Build
docker build -t jira-shift-roster .

# Run (H2)
docker run -p 8080:8080 \
  -e JIRA_URL=https://yourcompany.atlassian.net \
  -e JIRA_EMAIL=your-email@company.com \
  -e JIRA_API_TOKEN=your-token \
  -e JIRA_CUSTOM_JQL="your JQL" \
  jira-shift-roster

# Run (PostgreSQL)
docker run -p 8080:8080 \
  -e JIRA_URL=https://yourcompany.atlassian.net \
  -e JIRA_EMAIL=your-email@company.com \
  -e JIRA_API_TOKEN=your-token \
  -e JIRA_CUSTOM_JQL="your JQL" \
  -e DB_PROFILE=postgres \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/jiraassign \
  -e DB_USER=jiraassign \
  -e DB_PASSWORD=yourpassword \
  jira-shift-roster
```

---

## License

MIT
