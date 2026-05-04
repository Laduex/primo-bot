# Primo Bot

Standalone Discord bot service for Primo operations, with a Discord-authenticated web settings dashboard.

## Features

- `/order` flow: `Order From?` (forums under `Orders` category only) + `Customer name`, then send the next normal channel message as the order body
- `/completed` close command for forum posts (run inside a forum thread to archive/close it)
- `/vat` PH standard VAT calculator (12%), available in server channels and bot DMs
- Optional forum auto-mention: ping configured roles whenever a user creates a new post in selected forums
- Dashboard-managed orders reminder settings
- `/order-remind` admin command to manually send a reminder now for one configured forum route
- Daily branch reminders for unarchived order threads with friendly greeting and wiki-style links
- Dashboard-managed scheduled multi-account UTAK and Loyverse sales broadcasts
- Admin direct chat shortcut: send `sales run now` (or `sales run now account <account-id-or-name>`) to Primo and it sends the sales report directly in that chat
- Meta webhook relay endpoint to push new Messenger/Instagram inbound chats into a Discord channel
- Discord OAuth dashboard for guild-scoped settings at `/dashboard`
- Guild-specific command registration support via `DISCORD_GUILD_ID`
- Health endpoint on port `8086`

## Requirements

- Python 3.11+
- Discord bot token (`DISCORD_TOKEN`)
- Discord OAuth application for the dashboard

## Environment

Create `.env` from `.env.example`:

```bash
cp .env.example .env
```

Required variables:

- `DISCORD_TOKEN`
- `DISCORD_GUILD_ID` (optional default guild)
- `DISCORD_CLIENT_ID` (required for dashboard OAuth)
- `DISCORD_CLIENT_SECRET` (required for dashboard OAuth)
- `DISCORD_REDIRECT_URI` (required for dashboard OAuth callback)
- `DASHBOARD_SESSION_SECRET` (required for signed dashboard session cookies)

Optional dashboard variables:

- `DASHBOARD_BASE_URL` (optional absolute base URL used for documentation/deploy clarity)
- `DASHBOARD_CONFIG_PATH` (default: `/data/dashboard-config.json`)
- `FORUM_AUTO_MENTION_TARGETS` bootstrap default only; after first dashboard save, auto-mention settings are loaded from `DASHBOARD_CONFIG_PATH`

Orders reminder variables (all optional):

- `ORDER_REMINDER_CONFIG_PATH` (default: `/data/orders-reminder-config.json`)
- `ORDER_REMINDER_DEFAULT_ENABLED` (default: `true`)
- `ORDER_REMINDER_DEFAULT_TIME` (default: `08:00`)
- `ORDER_REMINDER_DEFAULT_TIMEZONE` (default: `Asia/Manila`)
- `ORDER_REMINDER_DEFAULT_ROUTES` (format: `forumId:channelId:roleId;...`)

Sales report variables (all optional bootstrap defaults):

- `SALES_REPORT_CONFIG_PATH` (default: `/data/sales-report-config.json`)
- `SALES_REPORT_DEFAULT_ENABLED` (default: `true`)
- `SALES_REPORT_DEFAULT_TIMEZONE` (default: `Asia/Manila`)
- `SALES_REPORT_DEFAULT_TIMES` (default: `09:00,12:00,15:00,18:00,21:00`)
- `SALES_REPORT_DEFAULT_TARGET_CHANNEL_ID` (default: empty)
- `SALES_REPORT_DEFAULT_TONE` (default: `casual`)
- `SALES_REPORT_DEFAULT_SIGNATURE` (default: `Thanks, Primo`)

Meta unread variables:

- `META_ACCESS_TOKEN` (required for Meta unread checks)
- `META_APP_SECRET` (optional, recommended for `appsecret_proof`)
- `META_GRAPH_VERSION` (default: `v24.0`)
- `META_API_BASE_URL` (default: `https://graph.facebook.com`)
- `META_WEBHOOK_VERIFY_TOKEN` (required for Meta webhook verification challenge)
- `META_WEBHOOK_TARGET_CHANNEL_ID` (Discord target channel for webhook relay)

Example:

```dotenv
FORUM_AUTO_MENTION_TARGETS=1345768901234567890:123456789012345678,223456789012345678;1456789012345678901:323456789012345678
ORDER_REMINDER_DEFAULT_ROUTES=1494503996357087443:1494175620287041536:1494215754084651089;1495586918589661384:1494175557208899736:1494215769347854356
SALES_REPORT_DEFAULT_TIMES=09:00,12:00,15:00,18:00,21:00
```

## Dashboard

Settings are managed in the Primo dashboard instead of slash config groups.

- Login: `GET /dashboard`
- OAuth callback: `GET /dashboard/oauth/callback`
- Guild list: `GET /dashboard/guilds`
- Guild settings: `GET /dashboard/guilds/{guildId}`

Dashboard sections:

- Orders reminders
- Sales schedules and delivery targets
- Sales accounts
- Forum auto-mention targets
- Meta unread digest settings
- Meta webhook target

## `/order-remind` command

Admin-only command (Manage Server permission required).

- `forum:<forum-channel>` sends the reminder immediately for that configured forum route
- Uses the same copy and greeting settings configured in the dashboard
- Marks that route as already sent for the day to prevent duplicate scheduled sends

Reminder behavior:

- Sends once per route per day at configured time
- Includes only unarchived forum posts
- Skips channels with no open orders
- Greeting buckets: `Good Morning`, `Good Afternoon`, `Good Evening`

## Sales report behavior

- UTAK metric: **Total Net Sales** (today cumulative)
- Loyverse metric: **Gross Sales Today** (today cumulative)
- Sales updates run on the configured update times and include account totals only
- Daily overview runs once on the configured summary time
- Daily overview adds per-account **Top 10 Sold SKU (PHP)** when SKU/item-level data is available
- Manual `run-now` sends a sales update format
- Friendly greeting buckets: `Good Morning`, `Good Afternoon`, `Good Evening`
- Partial failures still post with per-account warnings

## Meta webhook relay

When enabled in Meta App dashboard, incoming Messenger/Instagram chat events can be relayed directly to Discord.

- Verify URL: `GET /webhooks/meta`
- Event URL: `POST /webhooks/meta`
- Verification token: `META_WEBHOOK_VERIFY_TOKEN`
- Target channel: `META_WEBHOOK_TARGET_CHANNEL_ID`
- Supported Discord target types: text/news/thread direct send, forum post creation
- If `META_APP_SECRET` is set, webhook payloads must include a valid `X-Hub-Signature-256` header

## `/sales` command

Admin-only shortcut command for sending reports immediately.

- `run-now scope:<all|single> account:<optional>`
- If `scope:all`, report sends for all enabled accounts
- If `scope:single`, pick account using autocomplete by name

## Admin direct chat sales trigger

Admins (Manage Server permission) can also send plain text to Primo:

- `sales run now` -> runs all enabled accounts and sends the report directly in that chat
- `sales run now account <account-id-or-name>` -> runs a single account and sends it directly in that chat

## Run locally

```bash
cd python
python -m pip install -e ".[dev]"
uvicorn primobot_py.main:app --host 0.0.0.0 --port 8086
```

Health check:

- `http://localhost:8086/actuator/health`

## Run with Docker

```bash
docker compose up -d --build
```

## Python Canary Deploy (Non-Disruptive)

To run the Python branch as a sidecar canary without replacing the main bot:

```bash
docker compose --profile python-canary up -d --build primo-bot-python-canary
```

Canary health check:

- `http://localhost:18086/actuator/health`
