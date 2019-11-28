#!/usr/bin/env bash

set -e

chmod +x gradlew
chmod +x scripts/publish.sh

./scripts/publish.sh -f netTestDebug -s testnet -p ledger-connector -n ledger-connector
sleep 2
./scripts/publish.sh -f netMainDebug -p ledger-connector -n ledger-connector
sleep 2


./scripts/publish.sh -f netTestDebug -s testnet -p ledger-rxjava2-connector -n ledger-rxjava2-connector
sleep 2
./scripts/publish.sh -f netMainDebug -p ledger-rxjava2-connector -n ledger-rxjava2-connector
