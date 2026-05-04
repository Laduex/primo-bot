# Python Runtime

This folder contains the Python runtime for `primo-bot`.

## Included features

- FastAPI health endpoint and Meta webhook relay endpoint
- Discord slash command wiring for `/vat`, `/order`, `/completed`, `/order-remind`, and `/sales`
- Pending order capture from the user's next message
- Forum auto-mention for configured order forums
- Discord-authenticated settings dashboard with OAuth, guild selector, and JSON APIs
- Orders reminder config persistence, manual dispatch, and scheduler
- Sales report config persistence, UTAK/Loyverse providers, manual dispatch, and scheduler
- Meta unread config persistence, Graph API collection, digest delivery, and scheduler
- Fixture export for VAT, orders reminder copy, and sales report copy parity

## Run fixture export

```bash
python3 /Users/vaughndazo/Documents/LDX/Apps/primo-bot/python/scripts/export_fixtures.py
```
