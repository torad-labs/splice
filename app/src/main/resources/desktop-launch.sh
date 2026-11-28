# These origins, file paths and the companion are supplied by the install, not by Desktop.
on_door() {
  case "${1%/}" in
    "http://127.0.0.1:$port/door"|"http://localhost:$port/door"|"http://[::1]:$port/door"|\
    "http://127.0.0.1:$port/desktop"|"http://localhost:$port/desktop"|"http://[::1]:$port/desktop") return 0 ;;
    *) return 1 ;;
  esac
}
if [ "${CLAUDE_SPLICE_DESKTOP:-}" = 1 ]; then
  unset XDG_CONFIG_HOME CHROME_DESKTOP CLAUDE_SPLICE_DESKTOP CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS
fi
# Hooks need one owner for the session, not a new one for each callback.
if [ -f "$pipe" ] && [ -x "$pipe" ]; then
  export SPLICE_STATE_DIR
  export SPLICE_CONTROL_PORT="$port"
  if [ -r /proc/sys/kernel/random/uuid ]; then
    IFS= read -r SPLICE_FOREGROUND_OWNER </proc/sys/kernel/random/uuid
  else
    SPLICE_FOREGROUND_OWNER=$(/usr/bin/uuidgen)
  fi
  export SPLICE_FOREGROUND_OWNER
fi
if [ "${CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST:-}" = 1 ] && [ -r "$desktop_key" ]; then
  if on_door "${ANTHROPIC_BEDROCK_MANTLE_BASE_URL:-}" && [ -z "${AWS_BEARER_TOKEN_BEDROCK:-}" ]; then
    IFS= read -r AWS_BEARER_TOKEN_BEDROCK <"$desktop_key"
    export AWS_BEARER_TOKEN_BEDROCK
  fi
  if on_door "${ANTHROPIC_BASE_URL:-}"; then
    case "${CLAUDE_CODE_HOST_AUTH_ENV_VAR:-}" in
      ANTHROPIC_API_KEY) IFS= read -r ANTHROPIC_API_KEY <"$desktop_key"; export ANTHROPIC_API_KEY ;;
      *) IFS= read -r ANTHROPIC_AUTH_TOKEN <"$desktop_key"; export ANTHROPIC_AUTH_TOKEN ;;
    esac
  fi
fi
if [ -f "$pipe" ] && [ -x "$pipe" ]; then
  exec "$pipe" --program desktop --harness-map "$harness_map" --extra-settings "$hooks" "$@"
fi
exec "$@"
