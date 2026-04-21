# Primo Bot

Standalone Discord bot service for Primo operations slash commands.

## Features

- `/order` flow: `Order From?` (forums under `Orders` category only) + `Customer name`, then send the next normal channel message as the order body
- `/completed` close command for forum posts (run inside a forum thread to archive/close it)
- `/vat` PH standard VAT calculator (12%), available in server channels and bot DMs
- Optional forum auto-mention: ping configured roles whenever a user creates a new post in selected forums
- `/orders-reminder` admin command for daily open-order reminders
- Daily branch reminders for unarchived order threads with friendly greeting and wiki-style links
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

Example:

```dotenv
FORUM_AUTO_MENTION_TARGETS=1345768901234567890:123456789012345678,223456789012345678;1456789012345678901:323456789012345678
ORDER_REMINDER_DEFAULT_ROUTES=1494503996357087443:1494175620287041536:1494215754084651089;1495586918589661384:1494175557208899736:1494215769347854356
```

## `/orders-reminder` command

Admin-only command (Manage Server permission required).

- `status`
- `set-enabled enabled:<true|false>`
- `set-time hour:<0-23> minute:<0-59> timezone:<optional IANA timezone>`
- `set-route forum:<forum-channel> target:<text-channel> role:<role>`
- `remove-route forum:<forum-channel>`
- `set-copy tone:<casual|formal> signature:<text>`

Reminder behavior:

- Sends once per route per day at configured time
- Includes only unarchived forum posts
- Skips channels with no open orders
- Greeting buckets: `Good Morning`, `Good Afternoon`, `Good Evening`

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
