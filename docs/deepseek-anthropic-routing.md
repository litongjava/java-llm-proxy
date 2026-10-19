# DeepSeek Anthropic routing

Anthropic Messages requests using an API-key provider at the official
`https://api.deepseek.com` host now use `/anthropic/v1/messages` directly,
including providers configured as Responses/Codex. The selected model and API
key are retained. The provider is copied per request so other clients continue
using their configured protocol. Custom gateways, paths, OAuth credentials,
and token-count endpoints retain their existing routing.

Native routing preserves the Anthropic request and response formats, including
tool results, thinking, context management, and SSE events. Model aliases and
explicit payload rules still apply. DeepSeek remains responsible for which
Anthropic features it supports.

The generic Responses converter also normalizes tool output arrays to
`input_text`, `input_image`, and `input_file`. Previously, Anthropic tool-result
text blocks were copied with `type: text`, causing DeepSeek Responses to reject
the body with HTTP 422. The reference CLIProxyAPI Codex/Claude translator already
converts these blocks to `input_text`.

## Verification

- 200 Java tests passed.
- 32 HTTP protocol matrix cases passed against local mock upstreams.
- Request 701807521090285568 was replayed through an isolated packaged backend
  against the real DeepSeek Anthropic API. Streaming and non-streaming returned
  HTTP 200 and completed messages, with outgoing native payload equality checked.
  The output limit was reduced to 64 tokens for this test.
- No installed binaries or configuration were replaced.

For a local live replay, set `DEEPSEEK_API_KEY` in the process environment, then run
`python scripts/deepseek_native_replay.py --installation <directory> --request-log <error-log>`.
The script reads provider settings from the running desktop backend, starts an
isolated backend from the packaged JAR, and removes its temporary configuration
and captures when finished. It does not print the API key.

## Manual update

Exit the desktop application and its backend, back up the installed
`cpa-core/java-llm-proxy.jar`, then copy `target/java-llm-proxy-1.0.0.jar` over that
file, keeping the installed filename. Restart the desktop application.
No frontend executable update is required for this change.
