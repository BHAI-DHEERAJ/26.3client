#!/usr/bin/env bash
#
# Stores the Discord webhook in GitHub Actions without writing it to disk.
# The value is read with echo disabled and piped straight into `gh secret set`.
#
# Usage:
#   ./scripts/set-webhook-secret.sh
#   GH_REPO=owner/repo ./scripts/set-webhook-secret.sh
#
set -euo pipefail

repo="${GH_REPO:-BHAI-DHEERAJ/26.3client}"

if ! command -v gh >/dev/null 2>&1; then
    echo "The GitHub CLI (gh) is required: https://cli.github.com/" >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "Run 'gh auth login' first, with access to $repo." >&2
    exit 1
fi

printf 'Discord webhook URL (input hidden): '
IFS= read -r -s webhook
printf '\n'

if [[ -z "$webhook" ]]; then
    echo "No value entered. Nothing was changed." >&2
    exit 1
fi

case "$webhook" in
    https://discord.com/api/webhooks/* | https://canary.discord.com/api/webhooks/* | https://ptb.discord.com/api/webhooks/*)
        ;;
    *)
        echo "That is not a Discord HTTPS webhook URL. Nothing was changed." >&2
        exit 1
        ;;
esac

if [[ "$webhook" == *[[:space:]]* ]]; then
    echo "The URL contains whitespace. Nothing was changed." >&2
    exit 1
fi

printf '%s' "$webhook" | gh secret set DISCORD_WEBHOOK_URL --repo "$repo"
unset webhook

echo "Stored DISCORD_WEBHOOK_URL for $repo. Re-run the Android build workflow."
echo "The URL was not written to disk and is not printed by this script."
