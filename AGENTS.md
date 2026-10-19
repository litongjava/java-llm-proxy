# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java/nexus/io/llm/proxy/`: Java 21/tio-boot backend; HTTP handlers, services, validators, and interceptors.
- `src/main/resources/`: application defaults, logging configuration, and model catalogs.
- `src/test/java/`: JUnit tests mirroring backend packages.
- `frontend/src/`: React/TypeScript UI; `frontend/src-tauri/`: Rust desktop integration; `frontend/tests/`: frontend tests; `frontend/assets/`: images.
- `scripts/`: Python integration checks and release tooling. `docs/` contains provider and architecture notes. Preserve upstream attribution in `frontend/UPSTREAM.md` and bundled licenses.

## Build, Test, and Development Commands

Use JDK 21 and Maven; desktop development also requires Node.js, Rust, and platform Tauri prerequisites (MSVC/WebView2 on Windows).

- `mvn test '-Dgpg.skip'`: run Java tests.
- `mvn package '-DskipTests' '-Dgpg.skip' -Pproduction`: build the executable backend JAR; skipping tests is for packaging only.
- `mvn spring-boot:run`: start the backend with the default development profile.
- From `frontend/`, run `npm.cmd ci`, then `npm.cmd run tauri -- dev`: install dependencies and launch desktop development.
- `npm.cmd run check` / `npm.cmd run build`: type-check / compile and bundle the frontend.
- From `frontend/src-tauri/`, run `cargo test management_api::tests`: check management integration.
- After packaging, run `python scripts/protocol_matrix_test.py` for protocol integration checks.

Use `npm` instead of `npm.cmd` outside Windows.

## Coding Style & Naming Conventions

Match surrounding code: two-space indentation in Java and TypeScript, PascalCase classes/components, and camelCase methods/variables. Use descriptive `*Handler`, `*Service`, and `*Test` names. Keep handlers thin, obtain services through `Aop.get`, and return management responses with `RespBodyVo`; follow existing `Kv` and Java text-block usage. No dedicated formatter or lint configuration is present; avoid unrelated reformatting.

## Testing Guidelines

Backend tests use JUnit 4 (`@Test`); name classes `*Test.java`. Run focused cases with `mvn test '-Dtest=EmptyTextHistoryTest'`. No numerical coverage threshold is configured. Add regression coverage for changed protocol behavior, including streaming and tool calls where applicable. Use isolated configurations for integration tests. Browser development uses mock data: verify real account/kernel behavior in Tauri and capture screenshots for UI changes.

## Commit & Pull Request Guidelines

History mixes imperative summaries with `fix:` and `perf:` prefixes. Prefer concise English, scoped commits. PRs should explain the problem, resulting behavior, validation commands/results, linked issues when relevant, and screenshots for UI changes. Distinguish mocked checks from live-provider verification.

## Security & Configuration

Copy `secrets.example.txt` to local `secrets.txt` when needed. Never commit credentials, captured authentication headers, or local databases; never package `secrets.txt`. Backend configuration uses `EnvUtils`; the default port is `8318`.
