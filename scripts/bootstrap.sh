#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

skip_verify=false
with_observability=false
allow_legacy_cutover=false
for argument in "$@"; do
  case "${argument}" in
    --skip-verify) skip_verify=true ;;
    --observability) with_observability=true ;;
    --allow-legacy-cutover) allow_legacy_cutover=true ;;
    *) echo "Unknown argument: ${argument}" >&2; exit 2 ;;
  esac
done

require_command docker
require_command curl
docker info >/dev/null

if [[ ! -f "${ENV_FILE}" ]]; then
  umask 077
  temporary_env="$(mktemp "${PROJECT_ROOT}/.env.XXXXXX")"
  signing_directory="$(mktemp -d)"
  trap 'rm -rf -- "${signing_directory}" "${temporary_env}"' EXIT

  # macOS ships LibreSSL, whose `genpkey` does not support Ed25519. Probe
  # common OpenSSL 3 locations and only fall back to a pinned container when
  # the host has no compatible implementation.
  openssl_bin=""
  system_openssl="$(command -v openssl 2>/dev/null || true)"
  for candidate in \
    "${MARKETING_OPENSSL_BIN:-}" \
    /opt/homebrew/opt/openssl@3/bin/openssl \
    /usr/local/opt/openssl@3/bin/openssl \
    /opt/local/bin/openssl \
    "${system_openssl}"; do
    [[ -n "${candidate}" && -x "${candidate}" ]] || continue
    if (cd "${signing_directory}" && "${candidate}" genpkey -algorithm Ed25519 -out probe.pem >/dev/null 2>&1); then
      openssl_bin="${candidate}"
      rm -f -- "${signing_directory}/probe.pem"
      break
    fi
  done

  openssl_exec() {
    if [[ -n "${openssl_bin}" ]]; then
      (cd "${signing_directory}" && "${openssl_bin}" "$@")
    else
      docker run --rm -i \
        --volume "${signing_directory}:/keys" \
        --workdir /keys \
        alpine/openssl:3.5.3 "$@"
    fi
  }

  openssl_exec genpkey -algorithm Ed25519 -out offer.pem 2>/dev/null
  openssl_exec genpkey -algorithm Ed25519 -out release.pem 2>/dev/null
  openssl_exec genpkey -algorithm Ed25519 -out compiler.pem 2>/dev/null
  openssl_exec genpkey -algorithm Ed25519 -out runtime-ack.pem 2>/dev/null
  offer_private="$(openssl_exec pkey -in offer.pem -outform DER 2>/dev/null | openssl_exec base64 -A)"
  offer_public="$(openssl_exec pkey -in offer.pem -pubout -outform DER 2>/dev/null | openssl_exec base64 -A)"
  release_private="$(openssl_exec pkey -in release.pem -outform DER 2>/dev/null | openssl_exec base64 -A)"
  release_public="$(openssl_exec pkey -in release.pem -pubout -outform DER 2>/dev/null | openssl_exec base64 -A)"
  compiler_private="$(openssl_exec pkey -in compiler.pem -outform DER 2>/dev/null | openssl_exec base64 -A)"
  compiler_public="$(openssl_exec pkey -in compiler.pem -pubout -outform DER 2>/dev/null | openssl_exec base64 -A)"
  runtime_ack_private="$(openssl_exec pkey -in runtime-ack.pem -outform DER 2>/dev/null | openssl_exec base64 -A)"
  runtime_ack_public="$(openssl_exec pkey -in runtime-ack.pem -pubout -outform DER 2>/dev/null | openssl_exec base64 -A)"
  random_secret() { openssl_exec rand -hex 24; }
  {
    printf 'COMPOSE_PROJECT_NAME=marketing-r1\n'
    printf 'IMAGE_TAG=local\n'
    printf 'MARKETING_SECURITY_MODE=DEV\n'
    printf 'MARKETING_DEV_HEADERS_ENABLED=true\n'
    printf 'CONSOLE_DEMO_MODE=false\n'
    printf 'CONSOLE_AUTH_MODE=DEV\n'
    printf 'CONSOLE_API_BASE_URL=\n'
    printf 'CONSOLE_OIDC_AUTHORITY=http://host.docker.internal:8180/realms/marketing\n'
    printf 'CONSOLE_OIDC_CLIENT_ID=marketing-console\n'
    printf 'CONSOLE_OIDC_SCOPE="openid profile email"\n'
    printf 'DEV_INFRA_NETWORK=dev-infra\n'
    printf 'MARKETING_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'CONTROL_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'CONTROL_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'COMPILER_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'COMPILER_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'AUDIENCE_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'AUDIENCE_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'DECISION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'DECISION_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'BENEFIT_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'BENEFIT_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'EVENTS_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'EVENTS_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'JOURNEY_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'JOURNEY_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'ENGAGEMENT_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'ENGAGEMENT_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'MEASUREMENT_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'MEASUREMENT_MIGRATION_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'KEYCLOAK_DB_PASSWORD=%s\n' "$(random_secret)"
    printf 'KEYCLOAK_ADMIN_PASSWORD=%s\n' "$(random_secret)"
    printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$(random_secret)"
    printf 'OFFER_SIGNING_KEY_ID=offer-local-1\n'
    printf 'OFFER_SIGNING_PRIVATE_KEY_BASE64=%s\n' "${offer_private}"
    printf 'OFFER_SIGNING_PUBLIC_KEY_BASE64=%s\n' "${offer_public}"
    printf 'RELEASE_SIGNING_KEY_ID=release-local-1\n'
    printf 'RELEASE_SIGNING_PRIVATE_KEY_BASE64=%s\n' "${release_private}"
    printf 'RELEASE_SIGNING_PUBLIC_KEY_BASE64=%s\n' "${release_public}"
    printf 'COMPILER_SIGNING_KEY_ID=compiler-local-1\n'
    printf 'COMPILER_SIGNING_PRIVATE_KEY_BASE64=%s\n' "${compiler_private}"
    printf 'COMPILER_SIGNING_PUBLIC_KEY_BASE64=%s\n' "${compiler_public}"
    printf 'RUNTIME_ACK_SIGNING_KEY_ID=runtime-local-1\n'
    printf 'RUNTIME_ACK_SIGNING_PRIVATE_KEY_BASE64=%s\n' "${runtime_ack_private}"
    printf 'RUNTIME_ACK_SIGNING_PUBLIC_KEY_BASE64=%s\n' "${runtime_ack_public}"
    printf 'MARKETING_ROUTING_SECRET=%s\n' "$(random_secret)"
  } > "${temporary_env}"
  mv "${temporary_env}" "${ENV_FILE}"
  rm -rf -- "${signing_directory}"
  trap - EXIT
  echo "created private local configuration: ${ENV_FILE}"
fi

[[ -x "${MARKETING_INFRA_TOOL}" ]] || {
  echo "dev-infra is required at ${DEV_INFRA_ROOT}; set DEV_INFRA_ROOT to override." >&2
  exit 1
}
[[ -f "${DEV_INFRA_ENV_FILE}" ]] || {
  echo "Missing ${DEV_INFRA_ENV_FILE}; initialize dev-infra and configure its shared credentials first." >&2
  exit 1
}

legacy_mysql_container="${COMPOSE_PROJECT_NAME:-marketing-r1}-mysql-1"
if docker inspect "${legacy_mysql_container}" >/dev/null 2>&1; then
  if [[ "${allow_legacy_cutover}" != true || "${CONFIRM_DEV_INFRA_CUTOVER:-}" != "marketing-r1" ]]; then
    cat >&2 <<EOF
Legacy infrastructure container ${legacy_mysql_container} still exists.
Refusing to switch the running application to empty/shared dev-infra databases automatically.
Back up and migrate the legacy data first. To confirm a completed migration or an intentional
empty cutover, rerun with:
  CONFIRM_DEV_INFRA_CUTOVER=marketing-r1 ./scripts/bootstrap.sh --allow-legacy-cutover
EOF
    exit 2
  fi
fi

if [[ "${skip_verify}" == false ]]; then
  "${PROJECT_ROOT}/scripts/verify.sh" --fast
fi

if [[ "${with_observability}" == true ]]; then
  export MARKETING_OBSERVABILITY_ENABLED=true
  export TRACING_SAMPLE_PROBABILITY="${TRACING_SAMPLE_PROBABILITY:-0.10}"
  export DECISION_TRACING_SAMPLE_PROBABILITY="${DECISION_TRACING_SAMPLE_PROBABILITY:-0.01}"
  marketing_infra up-observability
else
  export MARKETING_OBSERVABILITY_ENABLED=false
  marketing_infra up
fi

compose up --detach --build --remove-orphans
if [[ "${with_observability}" == true ]]; then
  "${PROJECT_ROOT}/scripts/smoke.sh" --wait --observability
else
  "${PROJECT_ROOT}/scripts/smoke.sh" --wait
fi
"${PROJECT_ROOT}/scripts/seed.sh"

echo
echo "Marketing R1 is ready:"
echo "  Console       ${CONSOLE_URL}"
echo "  Gateway       ${GATEWAY_URL}"
echo "  Flink         ${FLINK_URL}"
echo "  Keycloak      ${KEYCLOAK_URL}"
if [[ "${with_observability}" == true ]]; then
  echo "  Grafana       ${GRAFANA_URL}"
  echo "  Prometheus    ${PROMETHEUS_URL}"
else
  echo "  Observability disabled (use --observability when needed)"
fi
