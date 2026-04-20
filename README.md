# Primo Bot

Standalone Discord bot service for Primo operations slash commands.

## Features

- `/order` flow: `Order From?` (forums under `Orders` category only) + `Customer name`, then send the next normal channel message as the order body
- `/vat` PH standard VAT calculator (12%), available in server channels and bot DMs
- `/recipe` admin command: list recipes or search recipe/variation names with ingredient costs, margins, and selling prices when available (server + DM support)
- `/supplier` admin command: list suppliers and show supplier profile details (server + DM support)
- Optional forum auto-mention: ping configured roles whenever a user creates a new post in selected forums
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
- `RECIPE_CALCULATOR_BASE_URL` (required for `/recipe` and `/supplier`, default `http://127.0.0.1:3001`)
- `RECIPE_CALCULATOR_EMAIL` (required for `/recipe` and `/supplier`)
- `RECIPE_CALCULATOR_PASSWORD` (required for `/recipe` and `/supplier`)
- `PRIMO_ADMIN_USER_IDS` (optional comma-separated Discord user IDs allowed to run admin commands in DMs)

Admin access rules:

- In guild channels: user must have `Administrator` permission.
- In DMs: user ID must be included in `PRIMO_ADMIN_USER_IDS`.

Example:

```dotenv
FORUM_AUTO_MENTION_TARGETS=1345768901234567890:123456789012345678,223456789012345678;1456789012345678901:323456789012345678
```

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
