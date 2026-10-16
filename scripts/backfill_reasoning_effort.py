"""Recover missing desktop usage effort from captured upstream requests.

Only empty effort fields are updated. Back up the usage database before applying.
"""
import argparse
import datetime
import json
import sqlite3
from pathlib import Path


def effort_from_body(raw):
    try:
        body = json.loads(raw) if isinstance(raw, str) else raw
        if not isinstance(body, dict):
            return None
        reasoning = body.get('reasoning')
        nested = reasoning.get('effort') if isinstance(reasoning, dict) else None
        for value in [nested, body.get('reasoning_effort')]:
            if isinstance(value, str) and value.strip():
                return value.strip()
    except (ValueError, TypeError):
        pass
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('install_directory', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    core_path = args.install_directory / 'cpa-core/data/proxy.db'
    usage_path = args.install_directory / 'usage-records/usage.db'
    with sqlite3.connect(core_path.as_uri() + '?mode=ro', uri=True) as core, \
            sqlite3.connect(usage_path.as_uri() + ('?mode=rw' if args.apply else '?mode=ro'), uri=True) as usage:
        updates = []
        for row_id, request_id in usage.execute("SELECT id, request_id FROM usage_events WHERE reasoning_effort = ''"):
            row = core.execute('SELECT body FROM requests WHERE id=?', (request_id,)).fetchone()
            if not row:
                continue
            try:
                detail = json.loads(row[0])
            except ValueError:
                continue
            # Do not infer the executed effort from the original request:
            # aliases or plugins may have changed or removed it upstream.
            effort = effort_from_body(detail.get('upstreamRequestBody'))
            if effort:
                updates.append((effort, row_id))
        print(f'Recoverable records: {len(updates)}')
        if args.apply and updates:
            suffix = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
            backup_path = usage_path.with_name(f'usage.before-reasoning-{suffix}.db')
            with sqlite3.connect(backup_path) as backup:
                usage.backup(backup)
            print(f'Backup: {backup_path}')
            with usage:
                usage.executemany("UPDATE usage_events SET reasoning_effort=? WHERE id=? AND reasoning_effort=''", updates)
            print(f'Updated records: {len(updates)}')


if __name__ == '__main__':
    main()
