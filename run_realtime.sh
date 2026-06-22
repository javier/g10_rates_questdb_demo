#!/usr/bin/env bash
# Real-time: one data-second per wall-second, stamped a couple of seconds ahead
# so a live dashboard stays ahead of WAL apply lag. Streams until Ctrl+C.
set -euo pipefail

HOSTS="${HOSTS:-127.0.0.1:9000}"
# For a LOCAL server built from master, export CLIENT_VERSION=1.3.5-SNAPSHOT.
CLIENT_VERSION="${CLIENT_VERSION:-1.3.2}"
# Real-time keeps store-and-forward (primary down -> buffer -> resume). If you switch
# the target to a different server build, clear the SF dir once: rm -rf /tmp/qwp_g10_sf

mvn -q -f ./pom.xml -Dquestdb.client.version="${CLIENT_VERSION}" compile exec:java -Dexec.args="--mode real-time \
    --hosts ${HOSTS} \
    --market_data_processes 2 --business_processes 1 \
    --market_data_min_eps 8000 --market_data_max_eps 12000 \
    --min_levels 10 --max_levels 10 \
    --core_min_eps 800 --core_max_eps 1400 \
    --rfqs_per_sec 0.3 --deal_ratio 0.35 \
    --total_market_data_events 0 \
    --create_views true"
