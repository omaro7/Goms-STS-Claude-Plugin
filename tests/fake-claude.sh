#!/usr/bin/env bash
# Mock Claude CLI: the prompt arrives on stdin (V1.2), arguments are echoed back.
prompt="$(cat)"
if [[ "$prompt" == *FAIL* ]]; then echo 'mock failure' >&2; exit 5; fi
if [[ "$prompt" == *SLOW* ]]; then sleep 12; fi
printf 'MOCK-CLAUDE: '
for arg in "$@"; do printf '%s ' "$arg"; done
printf '\nSTDIN: %s\n' "$prompt"
printf 'ENV MCP_TOOL_TIMEOUT=%s\n' "${MCP_TOOL_TIMEOUT:-unset}"
