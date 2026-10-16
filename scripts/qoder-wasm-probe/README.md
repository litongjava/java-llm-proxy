# Offline Qoder WASM probe

This standalone Maven project compares a Java Chicory bridge with OFM's existing Node bridge. It does not modify the application's dependencies, start a proxy, read installed Qoder credentials, or send network requests to Qoder. Maven may download public build dependencies.

Use Java 21 and Node with TypeScript transformation support. Supply the directory containing the original `qoder-wasm.ts` and `qoder-auth-wasm.wasm`:

```powershell
./run.ps1 -SourceDirectory $OriginalChannelSource
```

The probe verifies the expected binary hash, generates reference results with synthetic credentials, compares runtime authentication fields and the encrypted inference request, and tests cache decryption in both directions. Generated files stay under `target/probe` and are not release artifacts.

The deterministic random source and frozen clock are strictly for offline comparison. They must never be copied into production authentication or cryptographic code. The bridge models the browser crypto path; unexpected Node-only `require`/`call` paths deliberately report a bridge exception.

The original TypeScript bridge is adapted only in a generated temporary copy to point at the supplied WASM asset and expose its internal glue. The Java bridge is derived from that MIT-licensed bridge: Copyright (c) 2026 Jet, source https://gitee.com/iJetLi/deepseek-harness-codearts. The full license is retained in [channel-pack-MIT.txt](../../docs/licenses/channel-pack-MIT.txt). This repository does not redistribute the external WASM binary. See the separate binary-source discussion in [the analysis](../../docs/qoder-wasm-java-analysis.md).
