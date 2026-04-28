# Primo Bot

Standalone Discord bot service for Primo operations slash commands.

## Features

- `/order` flow: `Order From?` (forums under `Orders` category only) + `Customer name`, then send the next normal channel message as the order body
- `/completed` close command for forum posts (run inside a forum thread to archive/close it)
- `/vat` PH standard VAT calculator (12%), available in server channels and bot DMs
- Optional forum auto-mention: ping configured roles whenever a user creates a new post in selected forums
- `/orders-reminder` admin command for daily open-order reminders
- `/order-remind` admin command to manually send a reminder now for one configured forum route
- Daily branch reminders for unarchived order threads with friendly greeting and wiki-style links
- `/sales-report` admin command for scheduled multi-account UTAK and Loyverse sales broadcasts
- Admin direct chat shortcut: send `sales run now` (or `sales run now account <account-id-or-name>`) to Primo and it sends the sales report directly in that chat
- `/meta-unread` admin command for scheduled Meta unread digest checks (Facebook + Instagram where permission allows)
- Guild-specific command registration support via `DISCORD_GUILD_ID`
- Health endpoint on port `8086`

## Requirements

- Java 17+
- Maven 3.9+
- Discord bot token (`DISCORD_TOKEN`)

## Environment

Create `.env` from `.env.example`:

```bash
cp .env.example .env
```

Required variables:

- `DISCORD_TOKEN`
- `DISCORD_GUILD_ID` (optional default guild)
- `FORUM_AUTO_MENTION_TARGETS` (optional): semicolon-separated mapping in `forumId:roleId,roleId` format

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
- `META_UNREAD_CONFIG_PATH` (default: `/data/meta-unread-config.json`)
- `META_UNREAD_DEFAULT_ENABLED` (default: `false`)
- `META_UNREAD_DEFAULT_INTERVAL_MINUTES` (default: `15`)
- `META_UNREAD_DEFAULT_TARGET_CHANNEL_ID` (default: empty)

Example:

```dotenv
FORUM_AUTO_MENTION_TARGETS=1345768901234567890:123456789012345678,223456789012345678;1456789012345678901:323456789012345678
ORDER_REMINDER_DEFAULT_ROUTES=1494503996357087443:1494175620287041536:1494215754084651089;1495586918589661384:1494175557208899736:1494215769347854356
SALES_REPORT_DEFAULT_TIMES=09:00,12:00,15:00,18:00,21:00
```

## `/orders-reminder` command

Admin-only command (Manage Server permission required).

- `status`
- `set-enabled enabled:<true|false>`
- `set-time hour:<0-23> minute:<0-59> timezone:<optional IANA timezone>`
- `set-route forum:<forum-channel> target:<text-channel> role:<role>`
- `remove-route forum:<forum-channel>`
- `set-copy tone:<casual|formal> signature:<text>`

## `/order-remind` command

Admin-only command (Manage Server permission required).

- `forum:<forum-channel>` sends the reminder immediately for that configured forum route
- Uses the same copy/greeting settings from `/orders-reminder`
- Marks that route as already sent for the day to prevent duplicate scheduled sends

Reminder behavior:

- Sends once per route per day at configured time
- Includes only unarchived forum posts
- Skips channels with no open orders
- Greeting buckets: `Good Morning`, `Good Afternoon`, `Good Evening`

## `/sales-report` command

Admin-only command (Manage Server permission required).

- Schedule and channel:
- `status`
- `set-enabled enabled:<true|false>`
- `set-timezone timezone:<IANA timezone>`
- `add-time hour:<0-23> minute:<0-59>` (sales update slots)
- `remove-time hour:<0-23> minute:<0-59>` (sales update slots)
- `clear-times confirm:<true|false>` (sales update slots)
- `set-summary target:<text-or-forum-channel> hour:<0-23> minute:<0-59> timezone:<optional>` (daily overview slot)
- `set-channel target:<text-or-forum-channel>` (sales update channel)
- Run:
- `run-now target:<optional text-or-forum-channel> scope:<optional all|single> account:<optional>`
- `target` overrides destination for this run only (does not persist)
- `scope` defaults to `all`; if `account` is provided and `scope` is omitted, it runs as single-account
- `account` is required when `scope:single` and supports autocomplete by account name
- Accounts:
- `list-accounts`
- `add-account platform:<UTAK|LOYVERSE> name:<text> account-id:<optional> username:<optional> password:<optional> token:<optional> base-url:<optional> sales-url:<optional>`
- `update-account account-id:<text> ...`
- `remove-account account-id:<text>`
- `set-account-enabled account-id:<text> enabled:<true|false>`
- Copy:
- `set-copy tone:<casual|formal> signature:<text>`

Sales report behavior:

- UTAK metric: **Total Net Sales** (today cumulative)
- Loyverse metric: **Gross Sales Today** (today cumulative)
- Sales updates run on the configured update times and include account totals only
- Daily overview runs once on the configured summary time
- Daily overview adds per-account **Top 10 Sold SKU (PHP)** when SKU/item-level data is available
- Manual `run-now` sends a sales update format
- Friendly greeting buckets: `Good Morning`, `Good Afternoon`, `Good Evening`
- Partial failures still post with per-account warnings

## `/meta-unread` command

Admin-only command (Manage Server permission required).

- `status`
- `set-enabled enabled:<true|false>`
- `set-channel target:<channel>` (supports text/news/thread direct send and forum post flow)
- `set-interval minutes:<5-60>`
- `run-now`

Meta unread behavior:

- Polls Meta Graph directly using `META_ACCESS_TOKEN` (no `meta-mcp` dependency)
- Includes all connected pages from `/me/accounts`
- Checks Facebook + Instagram conversations every configured interval
- Includes conversations with `unread_count > 0` only
- Uses deterministic snippet summaries (no LLM)
- Reposts unread digest every interval until unread is cleared in Meta inbox
- Silent when unread total is zero
- Adds Instagram warning section in posted digest when IG permissions/capability are missing

## `/sales` command

Admin-only shortcut command for sending reports immediately.

- `run-now target:<text-or-forum-channel> scope:<all|single> account:<optional>`
- If `scope:all`, report sends for all enabled accounts
- If `scope:single`, pick account using autocomplete by name

## Admin direct chat sales trigger

Admins (Manage Server permission) can also send plain text to Primo:

- `sales run now` -> runs all enabled accounts and sends the report directly in that chat
- `sales run now account <account-id-or-name>` -> runs a single account and sends it directly in that chat

## Run locally

```bash
mvn clean package
java -jar target/primo-bot-1.0.0.jar
```

Health check:

- `http://localhost:8086/actuator/health`

## Run with Docker

```bash
docker compose up -d --build
```

## Python Canary Deploy (Non-Disruptive)

To run the Python branch as a sidecar canary without replacing the Java bot:

```bash
docker compose --profile python-canary up -d --build primo-bot-python-canary
```

Canary health check:

- `http://localhost:18086/actuator/health`
