#!/usr/bin/env bash

set -e

chmod +x gradlew
chmod +x scripts/publish.sh

./scripts/publish.sh -f netTestRelease -s testnet -p ledger-connector -n ledger-connector -t bintray
./scripts/publish.sh -f netMainRelease -p ledger-connector -n ledger-connector -t bintray

./scripts/publish.sh -f netTestRelease -s testnet -p ledger-rxjava2-connector -n ledger-rxjava2-connector -t bintray
./scripts/publish.sh -f netMainRelease -p ledger-rxjava2-connector -n ledger-rxjava2-connector -t bintray
