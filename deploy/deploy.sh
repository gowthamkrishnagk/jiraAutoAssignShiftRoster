#!/bin/bash
# ============================================================
#  deploy.sh — Jira Auto-Assign Shift Roster
#  Run from: /opt/jira-autoassign/
# ============================================================
set -e

APP_NAME="jira-autoassign"
JAR_PATH="/opt/jira-autoassign/app/autoassign-1.0.0.jar"
SERVICE_FILE="deploy/jira-autoassign.service"

echo "==> Deploying $APP_NAME"

# 1. Install Java 21 if missing
if ! java -version 2>&1 | grep -q "21"; then
  echo "==> Installing Java 21..."
  sudo apt update -y
  sudo apt install -y openjdk-21-jre-headless
fi

# 2. Set up PostgreSQL if missing
if ! command -v psql &>/dev/null; then
  echo "==> Installing PostgreSQL..."
  sudo apt install -y postgresql postgresql-contrib
  sudo systemctl enable postgresql
  sudo systemctl start postgresql
fi

# 3. Create DB and user (safe to run multiple times)
echo "==> Setting up database..."
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='jiraassign'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE DATABASE jiraassign;"
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='jiraassign'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE USER jiraassign WITH PASSWORD 'changeme';"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE jiraassign TO jiraassign;"

# 4. Install systemd service
echo "==> Installing systemd service..."
sudo cp "$SERVICE_FILE" /etc/systemd/system/jira-autoassign.service
sudo systemctl daemon-reload
sudo systemctl enable jira-autoassign

# 5. Start / restart
echo "==> Starting service..."
sudo systemctl restart jira-autoassign

sleep 3
sudo systemctl status jira-autoassign --no-pager

echo ""
echo "==> Done. App running at http://$(hostname -I | awk '{print $1}'):8080"
