# Jira Shift Roster Auto-Assign

A lightweight Spring Boot application that automatically manages Jira ticket assignments based on a monthly shift roster. Upload an Excel schedule once per month — the app assigns unassigned Jira tickets to whoever is currently on shift, round-robin across active shift members, and reassigns tickets when a shift ends.

---

## Features

- **Multi-Team Support** — manage Order Fallout, SAC, and any number of additional teams independently; each team has its own JQL, roster, dry-run toggle, and round-robin state
- **Parallel Scheduler** — all teams run simultaneously every minute, thread-safe, no team blocks another
- **Excel Upload with Preview** — upload a shift roster, review parsed data before saving
- **Auto Assign** — unassigned Jira tickets are assigned round-robin to active shift members every minute
- **Off-Shift Sweep** — when a shift ends, tickets held by the outgoing person are redistributed to whoever is currently on shift
- **Escalated Ticket Restore** — escalated unassigned tickets are restored to their last historical assignee
- **Per-Team Dry-Run** — each team can be toggled independently into test mode; the scheduler logs what would happen without calling the Jira API
- **Pause / Resume** — pause a specific person per team so they are skipped during assignment
- **Inline JQL Editor** — view and edit each team's JQL directly from the UI
- **Activity Log** — filterable per-team audit trail of the last 100 assignments
- **Date Range Expansion** — one Excel row covering a date range creates individual daily entries automatically
- **Overnight Shift Support** — shifts crossing midnight (e.g. 22:30 → 07:30) are handled correctly
- **No File Storage** — Excel is read in memory and discarded after parsing
- **Switchable Database** — H2 file-mode by default (no install), switch to PostgreSQL for production via a single flag

---

## How It Works

### 1. Prepare Excel

Create an `.xlsx` or `.xls` file with these column headers (case-insensitive, column order does not matter):

| Email | Shift Start | Shift End | From Date | To Date |
|---|---|---|---|---|
| alice@company.com | 07:00 | 16:00 | 01/03/2026 | 31/03/2026 |
| bob@company.com | 15:30 | 00:30 | 01/03/2026 | 15/03/2026 |
| bob@company.com | 07:00 | 16:00 | 16/03/2026 | 31/03/2026 |
| charlie@company.com | 22:30 | 07:30 | 01/03/2026 | 31/03/2026 |

- **Multiple people on same date** — add multiple rows with overlapping date ranges
- **Person changes shift mid-month** — add two rows for the same email with different date ranges (see Bob above)
- **Date formats accepted** — `dd/MM/yyyy`, `MM/dd/yyyy`, `yyyy-MM-dd`, or native Excel date cells
- **Time formats accepted** — `HH:mm`, `H:mm`, `HH:mm:ss`, or native Excel time cells
- **Re-uploading** replaces only the months present in the new file for that team — other teams and other months are untouched

### 2. Upload via Web UI

1. Open the app URL
2. Select the correct **team tab**
3. Drag & drop or click to select the Excel file
4. Click **Preview** — review parsed rows without touching the database
5. Click **Confirm & Save** — data is saved

### 3. Scheduler Runs Every Minute

For each team in parallel:
1. Finds who is currently on shift
2. Assigns all unassigned tickets (from the team's JQL) round-robin to active shift members
3. Restores escalated + unassigned tickets to their last historical assignee
4. Sweeps tickets held by off-shift people and redistributes them to the active shift

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.4 |
| Database | H2 (local) / PostgreSQL (production) — switchable via profile |
| ORM | Spring Data JPA / Hibernate |
| Excel Parsing | Apache POI 5.2.5 |
| Frontend | Vanilla HTML / CSS / JS |
| Scheduler | Spring `@Scheduled` (every minute, parallel per team) |
| Hot Reload | Spring Boot DevTools |

---

## Project Structure

```
src/main/
├── java/com/jira/autoassign/
│   ├── JiraAutoAssignApplication.java
│   ├── client/
│   │   └── JiraClient.java                # Jira REST API calls
│   ├── config/
│   │   ├── AppConfig.java                 # Spring beans
│   │   ├── DataInitializer.java           # Seeds default teams on first boot
│   │   └── JiraProperties.java            # jira.* config properties
│   ├── controller/
│   │   ├── AssignController.java          # manual trigger endpoint
│   │   └── UploadController.java          # all REST endpoints
│   ├── entity/
│   │   ├── AssignmentLog.java
│   │   ├── ShiftRoster.java
│   │   └── Team.java
│   ├── repository/
│   │   ├── AssignmentLogRepository.java
│   │   ├── ShiftRosterRepository.java
│   │   └── TeamRepository.java
│   ├── scheduler/
│   │   └── AssignScheduler.java           # cron trigger
│   └── service/
│       ├── ExcelService.java              # Excel parsing + save
│       └── ShiftAssignService.java        # assign/unassign logic
└── resources/
    ├── application.properties             # common config + active profile
    ├── application-h2.properties          # H2 file-mode (local dev)
    ├── application-postgres.properties    # PostgreSQL (production)
    └── static/
        ├── index.html                     # main UI
        └── activity.html                  # activity log UI
```

---

## Database

Three tables are created automatically on first boot (`ddl-auto=update`) — no manual SQL needed.

| Table | Description |
|---|---|
| `teams` | Team config — id, name, JQL, dry_run flag |
| `shift_roster` | Expanded daily shift entries per team |
| `assignment_log` | Audit trail of every ticket assignment |

Two default teams are seeded on first startup if they don't exist:

| ID | Name | JQL basis |
|---|---|---|
| `orderfallout` | Order Fallout | `jira.custom-jql` from config |
| `sac` | SAC Team | Same JQL with `Reporting Area != "Order Fallout"` |

Additional teams can be added at runtime from the UI → **+ Add Team**.

---

## Database Profiles

### Local — H2 (default, no install needed)

```bash
mvn spring-boot:run
```

- Data stored in `./data/jiraassign.mv.db`
- Survives restarts
- Browser console at `http://localhost:8080/h2-console`
  - JDBC URL: `jdbc:h2:file:./data/jiraassign`
  - Username: `sa` / Password: *(leave blank)*

### Production — PostgreSQL (Ubuntu server)

**One-time DB setup:**
```bash
sudo -u postgres psql -c "CREATE DATABASE jiraassign;"
sudo -u postgres psql -c "CREATE USER jiraassign WITH PASSWORD 'changeme';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE jiraassign TO jiraassign;"
```

**Run the app:**
```bash
java -jar app.jar --spring.profiles.active=postgres
```

Or via environment variable:
```bash
export DB_PROFILE=postgres
mvn spring-boot:run
```

Override connection details if needed:
```bash
export DB_URL=jdbc:postgresql://localhost:5432/jiraassign
export DB_USER=jiraassign
export DB_PASSWORD=yourpassword
```

---

## Local Development

**Prerequisites:** Java 21, Maven

```bash
# Clone
git clone https://github.com/gowthamkrishnagk/jiraAutoAssignShiftRoster.git
cd jiraAutoAssignShiftRoster

# Set Jira credentials
export JIRA_URL=https://yourcompany.atlassian.net
export JIRA_EMAIL=your-email@company.com
export JIRA_API_TOKEN=your-token
export JIRA_CUSTOM_JQL="project = SAC AND status in ('In Progress') AND Assignee in (EMPTY)"

# Run (H2 used by default — no DB install needed)
mvn spring-boot:run
```

Open `http://localhost:8080`

**Hot reload** — after editing any `.java` file, just recompile and the server restarts automatically:
```bash
# In a second terminal
mvn compile
```
Or press `Ctrl+F9` in IntelliJ. Changes to HTML/JS in `src/main/resources/static/` are picked up instantly without restart.

---

## REST API

### Teams

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/teams` | List all teams |
| `POST` | `/api/teams` | Add a new team `{name, jql}` |
| `PUT` | `/api/teams/{id}` | Update team name or JQL |
| `DELETE` | `/api/teams/{id}` | Delete a team |

### Roster & Status

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/preview` | Parse Excel, return rows for review — nothing saved |
| `POST` | `/api/upload?team={id}` | Parse, expand, and save shift roster for a team |
| `GET` | `/api/status?team={id}` | Current active shift assignees for a team |
| `GET` | `/api/schedule?team={id}` | This month's full schedule for a team |
| `GET` | `/api/activity` | Last 100 assignment log entries (all teams) |

### Control

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/config?team={id}` | Get dry-run state for a team |
| `POST` | `/api/config/dry-run` | Set dry-run `{teamId, dryRun: true/false}` |
| `POST` | `/api/shift/pause` | Pause assignee `{teamId, email}` |
| `POST` | `/api/shift/resume` | Resume assignee `{teamId, email}` |
| `POST` | `/api/assign/run` | Manually trigger assignment run for all teams |

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|---|---|---|---|
| `JIRA_URL` | Yes | — | Jira instance URL |
| `JIRA_EMAIL` | Yes | — | Jira login email |
| `JIRA_API_TOKEN` | Yes | — | Jira API token |
| `JIRA_CUSTOM_JQL` | Yes | — | JQL for Order Fallout team (SAC JQL is derived from this) |
| `JIRA_DRY_RUN` | No | `false` | Global dry-run default on first boot |
| `JIRA_SCHEDULE_CRON` | No | `0 */1 * * * *` | Cron expression for scheduler |
| `DB_PROFILE` | No | `h2` | `h2` for local, `postgres` for production |
| `DB_URL` | No | `jdbc:postgresql://localhost:5432/jiraassign` | PostgreSQL JDBC URL |
| `DB_USER` | No | `jiraassign` | PostgreSQL username |
| `DB_PASSWORD` | No | `changeme` | PostgreSQL password |
| `PORT` | No | `8080` | HTTP server port |

> **How to get a Jira API token:** Jira → Profile → Security → Create and manage API tokens

---

## Deployment — PROUBUNTU022

### Quick Deploy

**1. Build JAR locally**
```bash
mvn clean package -DskipTests
```

**2. SCP package to server**
```bash
scp -r jiraAutoAssignShiftRoster/ user@10.169.101.69:/opt/jira-autoassign/
```

**3. Run deploy script**
```bash
cd /opt/jira-autoassign
bash deploy/deploy.sh
```

**4. Hand Nginx config to IT**
```
deploy/nginx-jira-autoassign.conf
```

---

### Manual Steps (if not using deploy.sh)

```bash
# Install Java 21
sudo apt update
sudo apt install -y openjdk-21-jre-headless

# Install PostgreSQL
sudo apt install -y postgresql postgresql-contrib
sudo systemctl enable postgresql
sudo systemctl start postgresql

# Create database and user
sudo -u postgres psql -c "CREATE DATABASE jiraassign;"
sudo -u postgres psql -c "CREATE USER jiraassign WITH PASSWORD 'changeme';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE jiraassign TO jiraassign;"

# Start manually (test)
java -jar /opt/jira-autoassign/app/autoassign-1.0.0.jar \
  --spring.profiles.active=postgres \
  --DB_URL=jdbc:postgresql://localhost:5432/jiraassign \
  --DB_USER=jiraassign \
  --DB_PASSWORD=changeme

# Install as service
sudo cp deploy/jira-autoassign.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable jira-autoassign
sudo systemctl start jira-autoassign
```

---

### First-Time Configuration

1. Edit credentials in the service file before starting:
```bash
sudo nano /etc/systemd/system/jira-autoassign.service
```
Fill in:
- `JIRA_URL` → `https://yourcompany.atlassian.net`
- `JIRA_EMAIL` → your Jira login email
- `JIRA_API_TOKEN` → generate at https://id.atlassian.com/manage-profile/security/api-tokens
- `JIRA_CUSTOM_JQL` → your Order Fallout JQL query
- `DB_PASSWORD` → your chosen PostgreSQL password

2. Reload and restart after editing:
```bash
sudo systemctl daemon-reload
sudo systemctl restart jira-autoassign
```

3. Open `http://<server-ip>:8080`

4. Switch to each team tab and upload the monthly Excel roster

5. Tables are created automatically on first boot — no SQL needed

---

### Port Reference

| App | Port |
|---|---|
| soql-monitor | 9002 |
| Fallout App | 8001 |
| SAC Dashboard | 8002 |
| **Jira Auto-Assign** | **8080** |

---

### Useful Commands

```bash
# Check service status
sudo systemctl status jira-autoassign

# View live logs
sudo journalctl -u jira-autoassign -f

# Restart after update
sudo systemctl restart jira-autoassign

# Stop service
sudo systemctl stop jira-autoassign

# Deploy a new JAR
scp target/autoassign-1.0.0.jar user@10.169.101.69:/opt/jira-autoassign/app/
sudo systemctl restart jira-autoassign

# Connect to database
sudo -u postgres psql -d jiraassign

# Check active shifts (DB query)
sudo -u postgres psql -d jiraassign -c "SELECT * FROM shift_roster WHERE shift_date = CURRENT_DATE;"

# Check last 10 assignments
sudo -u postgres psql -d jiraassign -c "SELECT * FROM assignment_log ORDER BY assigned_at DESC LIMIT 10;"
```

---

## Docker

```bash
# Build
docker build -t jira-shift-roster .

# Run with H2
docker run -p 8080:8080 \
  -e JIRA_URL=https://yourcompany.atlassian.net \
  -e JIRA_EMAIL=your-email@company.com \
  -e JIRA_API_TOKEN=your-token \
  -e JIRA_CUSTOM_JQL="your JQL" \
  jira-shift-roster

# Run with PostgreSQL
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
