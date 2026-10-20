#!/usr/bin/env bash
# Build da imagem, import no k3d, Secret a partir do .env e apply dos manifests.
set -euo pipefail
export PATH=$HOME/.local/bin:$PATH
cd "$(dirname "$0")"
API=..

docker build -t validation-flow-engine:local "$API"
k3d image import validation-flow-engine:local -c dev

kubectl apply -f namespace.yaml
kubectl -n validation create secret generic validation-env --from-env-file="$API/.env" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -k .
kubectl -n validation rollout restart deploy/api
