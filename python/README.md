# Python Refactor Scaffold

This folder contains the Python migration scaffold for `primo-bot`.

## Current focus

- Parity-first implementation for deterministic business logic:
- VAT calculations
- Orders reminder message composition
- Sales report message composition
- Fixture export for Java vs Python comparison

## Run fixture export

```bash
python3 /Users/vaughndazo/Documents/LDX/Apps/primo-bot/python/scripts/export_fixtures.py
```

## Planned next

- Discord slash command wiring (`discord.py`)
- Scheduler parity for reminders and sales report jobs
- Config store parity with existing JSON files
