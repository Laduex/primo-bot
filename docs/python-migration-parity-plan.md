# Python Refactor + 1:1 Parity Plan

This branch tracks migration from Java (`main`) to Python with strict behavior parity.

## Goal

Ship a Python bot that is behavior-equivalent to the current Java bot before any production cutover.

## Scope To Match 1:1

- Slash commands: `/order`, `/completed`, `/vat`, `/orders-reminder`, `/order-remind`, `/sales-report`, `/sales`
- Admin direct chat trigger: `sales run now` (all and single-account variants)
- Scheduled jobs:
- Orders reminder timing, per-route daily-send guard, greeting buckets
- Sales update slots and daily summary behavior
- Config persistence behavior for reminders and sales report settings
- Permission checks (admin/manage-server-only command access)
- Error handling behavior (partial failures still posting with warnings)

## Migration Guardrails

1. Keep Java branch (`main`) as source of truth until parity is proven.
2. Build Python version on this branch only.
3. Do not deploy Python to production until all parity checks pass.
4. Keep rollback simple: Java artifact and compose config must remain runnable.

## Parity Verification Workflow

1. Define test scenarios for each command and scheduler behavior.
2. Capture Java outputs for each scenario as JSON fixtures.
3. Capture Python outputs for the same scenarios as JSON fixtures.
4. Compare fixtures using `tools/parity/compare_json.py`.
5. Run side-by-side in a non-production Discord test guild.
6. Run smoke checklist in staging compose stack.
7. Cut over only after explicit pass on all checks.

## Required Pass Criteria Before Production

- All scenario fixture pairs are identical (`compare_json.py` exit code 0).
- All slash commands respond with same user-visible content and permissions.
- Scheduled jobs fire at same expected time windows/timezone logic.
- Config read/write behavior matches for existing config files.
- No increase in failed command rate during staging soak.

## Suggested Implementation Order

1. Bot boot + command registration
2. Command handlers (non-scheduled)
3. Config stores
4. Reminder scheduler
5. Sales scheduler + providers
6. Final parity run + staging soak

## Cutover Checklist

- Python image built and pinned by digest
- Java image retained for rollback
- Environment variables validated against Python runtime
- Production deployment window selected
- Rollback command and owner confirmed
