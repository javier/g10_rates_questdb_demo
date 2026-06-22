#!/usr/bin/env bash
# Backfill (faster-than-life) a window of G10 rates data.
# Profile A: a single active day generates in ~minutes for live screen-share.
# Profile B: pass a wider --start_ts/--end_ts (e.g. one month) for the tiering story.
#
# Local OSS instance (no auth) by default; override HOSTS / add --token_file etc. for a cluster.
set -euo pipefail

HOSTS="${HOSTS:-127.0.0.1:9000}"
# Default to the Maven-Central release (cluster/demo). For a LOCAL server built from
# master, export CLIENT_VERSION=1.3.5-SNAPSHOT (the QWP wire must match the server build).
CLIENT_VERSION="${CLIENT_VERSION:-1.3.2}"
SF_DIR="${SF_DIR:-/tmp/qwp_g10_sf}"
# Clear store-and-forward before a backfill so stale frames from a different server
# build can't poison-replay (harmless on a fresh dir; keep SF for real demo runs).
rm -rf "${SF_DIR}"

mvn -q -f ./pom.xml -Dquestdb.client.version="${CLIENT_VERSION}" compile exec:java -Dexec.args="--mode faster-than-life \
    --sf_dir ${SF_DIR} \
    --hosts ${HOSTS} \
    --market_data_processes 2 --business_processes 1 \
    --market_data_min_eps 8000 --market_data_max_eps 12000 \
    --min_levels 10 --max_levels 10 \
    --core_min_eps 800 --core_max_eps 1400 \
    --rfqs_per_sec 0.3 --deal_ratio 0.35 \
    --start_ts 2026-06-22T00:00:00.000000Z --end_ts 2026-06-23T00:00:00.000000Z \
    --total_market_data_events 900000000 \
    --create_views true"
