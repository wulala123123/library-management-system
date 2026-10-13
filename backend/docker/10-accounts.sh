#!/usr/bin/env bash
set -euo pipefail
# The MySQL entrypoint creates library_app first. Give it DML-only access;
# Flyway uses a separate schema migration account.
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo 'Invalid database name' >&2; exit 1; }
[[ "$MYSQL_MIGRATION_PASSWORD" =~ ^[A-Za-z0-9_-]{24,128}$ ]] || { echo 'Migration password must use 24-128 letters, digits, underscores or hyphens' >&2; exit 1; }
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -u root <<SQL
REVOKE ALL PRIVILEGES ON \`$MYSQL_DATABASE\`.* FROM 'library_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$MYSQL_DATABASE\`.* TO 'library_app'@'%';
CREATE USER 'library_migrator'@'%' IDENTIFIED BY '$MYSQL_MIGRATION_PASSWORD';
GRANT ALL PRIVILEGES ON \`$MYSQL_DATABASE\`.* TO 'library_migrator'@'%';
SQL
