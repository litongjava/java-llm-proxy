# Multi-Protocol providers

API Access lists **Multi-Protocol** after Meta. Add a named provider, select its
supported protocols, and enter a Base URL for each. The first selected protocol
becomes the default conversion protocol; removing it requires selecting another
default before saving. Credentials, model mappings, headers and proxy settings
use the existing provider group controls.

Supported protocols are OpenAI Chat Completions (`openai`), OpenAI Responses
(`responses`), Anthropic Messages (`anthropic`), and Gemini (`gemini`). Protocol
support applies to every model configured in the group. The endpoint preview
shows the existing API-prefix convention, including Anthropic's `/v1` suffix.

```yaml
api-keys:
  multi-protocol:
    - name: Example gateway
      default-protocol: responses
      protocol-endpoints:
        responses: https://gateway.example/v1
        anthropic: https://gateway.example/anthropic
      keys:
        - api-key: YOUR_API_KEY
      models:
        - name: example-model
```

Matching incoming protocols use their native endpoint and response format.
Unsupported protocols use the configured default and existing converters.
Native-capable providers participate in native-priority selection. Provider
resolution uses a request-local copy; concurrent clients cannot change another
request's protocol. Explicit multi-protocol settings take precedence over the
DeepSeek compatibility shortcut. Existing DeepSeek settings are not migrated.
An upstream error does not trigger a switch to a different protocol.

The management config node is `api-keys/multi-protocol`. Runtime provider JSON
uses `protocolEndpoints`, with `protocol` and `baseUrl` holding the default.

## Verification

- 202 Java tests, 85 focused frontend tests and 13 Rust management tests passed.
- Frontend production build and Rust desktop build passed.
- `python scripts/multi_protocol_test.py`: eight native HTTP cases, protocol
  authentication, backend restart persistence and two conversion cases passed
  with an isolated backend and mock upstream.
- Existing 32-case protocol HTTP matrix passed.
- `frontend/tests/multi-protocol-ui.cjs`: visible Chrome create/save/reload/edit
  passed with mock IPC; screenshots are in `docs/assets/multi-protocol-*.png`.
- The same isolated fixture was also exercised using the locally installed dsb
  CLI in visible Chrome: creating both endpoints, saving, reloading, switching
  the default to Anthropic, and reloading again preserved the selected endpoint.
- Real Tauri acceptance remains unverified: the isolated executable exited in
  its setup hook with `the underlying handle is not available`, before the
  WebView debugging connection became available. The reproduction is
  `frontend/tests/multi-protocol-tauri.cjs`.

No installed files were replaced. This feature needs both a rebuilt desktop
frontend and backend when manually updating; replacing only the JAR does not
add the new UI.
