#!/usr/bin/env bash
# listusers.sh — Lists all users in digitaltwinapp.users

docker exec global_banking_db psql -U admin banking_system -c \
    "SELECT user_id, email, name, status FROM digitaltwinapp.users ORDER BY user_id;"
