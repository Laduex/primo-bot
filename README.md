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
