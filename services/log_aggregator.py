#!/usr/bin/env python3

import json
import os
from datetime import datetime
from pathlib import Path

LOG_SOURCES = [
    '/app/logs/identity/http_extended.jsonl',
    '/app/logs/workshop/http_extended.jsonl',
    '/app/logs/community/http_extended.jsonl',
]

OUTPUT_FILE = '/app/logs/aggregated_logs.jsonl'

def aggregate_logs():
    all_entries = []

    for source in LOG_SOURCES:
        if os.path.exists(source):
            with open(source, 'r') as f:
                for line in f:
                    try:
                        entry = json.loads(line.strip())
                        entry['source'] = Path(source).parent.name
                        all_entries.append(entry)
                    except json.JSONDecodeError:
                        continue

    all_entries.sort(key=lambda x: x.get('timestamp', ''))

    with open(OUTPUT_FILE, 'w') as f:
        for entry in all_entries:
            f.write(json.dumps(entry, ensure_ascii=False) + '\n')

    print(f"Aggregated {len(all_entries)} log entries to {OUTPUT_FILE}")

if __name__ == '__main__':
    aggregate_logs()
