# CX configuration support

CX is an independent Agent Configuration client (`cx`) using the Codex configuration format. The launcher project lives at `D:\code\project\project-harven\cx`.

The GUI reads `%USERPROFILE%\.cx\config.toml`, `.cx/auth.json`, and `.cx/cpa-gui-model-catalog.json` (`CX_HOME` overrides the directory). Discovery prefers `.cx/bin/cx.cmd`; manual executable overrides are also supported. Apply/update, template, backup, restoration and launch use the CX identity and directory. The proxy endpoint is `/tools/cx/v1`, with a separate tool alias scope.

CX uses the generic transactional configuration workflow. It does not use Codex's native OAuth switch or desktop/daemon commands. OAuth login remains available through `cx login`; applying proxy configuration in the GUI writes CX API credentials. Existing Codex configuration/login is never used as a fallback.

Session sharing is implemented by the separate launcher; the GUI does not copy, migrate or delete session data. See its README for installation and hard-link replacement limitations.

The launcher now shares skills, plugins, cache, sandbox directories, user instructions and other persistent user data too. General TOML settings synchronize at CX startup/exit; connection fields (provider, endpoint, credentials and provider-specific catalog) remain independent. GUI changes to general CX settings propagate on its next launch. Per-process temporary files and daemon control state stay separate to preserve authentication isolation. Migration backups live in `%USERPROFILE%/.cx-sharing-backups`. New source installations need `npm ci` for the launcher's TOML parser.

Validation: `cargo test --manifest-path frontend/src-tauri/Cargo.toml cx_`, agent transaction/backups regression tests, frontend TypeScript/build, `frontend/tests/cx-configuration-ui.cjs` (Vite at 1421), and launcher unit/integration checks.

Rebuild/restart the desktop application and Java backend to use the new client and tool scope in an installed application.

## Verification results

- Launcher unit tests: 2 passed; actual Codex/CX app-server checks listed identical 20 recent sessions, with unchanged original config/auth hashes.
- CX Rust tests: 2 passed. Agent module regression suite: 125 passed, 1 ignored.
- CX browser UI test, TypeScript check and production frontend build passed.
- Java `ToolAliasRoutingTest` passed.
- Wider `tests::agent_` suite: 139 passed, 4 failed, 1 ignored. A temporary copy with this task's Rust changes reverted to HEAD reproduced the same four failures: `claude_desktop_uses_selected_alias_directly_with_original_context`, `agent_command_path_prioritizes_the_executable_and_preserves_discovered_paths`, `claude_desktop_aliases_expose_role_routes_only`, and `version_three_cleanup_covers_every_non_desktop_agent_format`.
