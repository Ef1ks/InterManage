#!/usr/bin/env bash
# Podmienia bazę bety na świeżą kopię produkcji. Uruchamiać w LXC Postgresa (192.168.33.110) jako postgres:
#   sudo -u postgres bash beta-refresh-db.sh
# Przedtem zatrzymaj backend bety (na 192.168.33.113: cd /opt/intermanage-beta && docker compose stop backend),
# potem go uruchom (docker compose up -d): Flyway dołoży migracje z gałęzi beta, których produkcja jeszcze nie ma.
# Produkcja jest tylko czytana (pg_dump), nic w niej się nie zmienia.
set -euo pipefail

PROD_DB=intermanage_prod
BETA_DB=intermanage_beta
BETA_USER=intermanage_beta
DUMP=$(mktemp /tmp/intermanage-beta-XXXXXX.dump)
trap 'rm -f "$DUMP"' EXIT

echo "Kopia $PROD_DB…"
pg_dump --format=custom --no-owner --no-privileges "$PROD_DB" > "$DUMP"

echo "Nowa baza $BETA_DB…"
psql -v ON_ERROR_STOP=1 -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$BETA_DB' and pid <> pg_backend_pid();" > /dev/null
psql -v ON_ERROR_STOP=1 -d postgres -c "drop database if exists $BETA_DB;"
psql -v ON_ERROR_STOP=1 -d postgres -c "create database $BETA_DB owner $BETA_USER;"
# Rozszerzenia, które może założyć tylko superużytkownik (pg_restore z --role nie da rady).
psql -v ON_ERROR_STOP=1 -d "$BETA_DB" -c "create extension if not exists pg_prewarm;"

echo "Odtwarzanie…"
pg_restore --no-owner --role="$BETA_USER" --dbname="$BETA_DB" "$DUMP"

echo "Gotowe: $BETA_DB jest kopią $PROD_DB z $(date '+%F %T')."
