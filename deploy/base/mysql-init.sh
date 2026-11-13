#!/bin/sh
# The official entrypoint may source a non-executable init script on Linux.
# Keep shell options and credential variables out of that parent shell.
(
set -eu
# The official entrypoint runs this only while initializing an empty owned volume.
# Input files contain generated hexadecimal passwords; no credentials appear in argv or SQL logs.
case "$DB_NAME" in campuslife|campuslife_[a-z0-9]*) ;; *) exit 64 ;; esac
case "$DB_NAME" in *[!a-z0-9_]*) exit 64 ;; esac
app_password=$(cat /run/secrets/DB_PASSWORD)
case "$app_password" in ''|*[!a-f0-9]*) exit 64 ;; esac
MYSQL_PWD=$(cat /run/secrets/MYSQL_ROOT_PASSWORD)
export MYSQL_PWD
mysql --protocol=socket --user=root <<SQL
CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'campuslife'@'%' IDENTIFIED WITH caching_sha2_password BY '$app_password';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON \`$DB_NAME\`.* TO 'campuslife'@'%';
SQL
unset MYSQL_PWD app_password
)
