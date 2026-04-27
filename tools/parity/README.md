# Parity Fixtures

Use this folder to compare Java bot behavior vs Python bot behavior using JSON fixtures.

## Directory layout

```text
tools/parity/
  compare_json.py
  README.md
  fixtures/
    java/
    python/
```

## How to run

1. Export Java baseline fixtures:

```bash
./tools/parity/export_java_fixtures.sh
```

2. Export Python fixtures:

```bash
./tools/parity/export_python_fixtures.sh
```

3. Ensure file names and structure match for equivalent scenarios.
4. Run comparator:

```bash
python3 tools/parity/compare_json.py \
  --java tools/parity/fixtures/java \
  --python tools/parity/fixtures/python
```

## Fixture guidance

Each fixture should represent one deterministic scenario, for example:

- `commands/vat/basic.json`
- `commands/orders-reminder/status.json`
- `commands/sales-report/run-now-all.json`
- `schedulers/orders-reminder/morning-route-1.json`

Include both visible output and relevant metadata needed to verify behavior parity.
