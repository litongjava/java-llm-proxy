use super::*;

pub(crate) fn managed_tool_origin(port: u16, tool: &str) -> String {
    format!("{}/tools/{tool}", managed_core_loopback_origin(port))
}

fn validate_tool(tool: &str) -> Result<(), String> {
    if tool == "pi" { return Ok(()); }
    AgentClient::parse(tool).map(|_| ())
}

fn parse_document(content: &str) -> Result<serde_norway::Value, String> {
    serde_norway::from_str(content).map_err(|error| format!("Invalid alias configuration: {error}"))
}

fn provider_identity(section: &str, provider: &serde_norway::Value) -> String {
    let text = |key| provider.get(key).and_then(serde_norway::Value::as_str).unwrap_or("");
    let key = if section == "openai-compatibility" { text("name") } else { text("api-key") };
    sha256_bytes(format!("{section}\n{}\n{}\n{key}", text("base-url"), text("prefix")).as_bytes())
}

/// Build an isolated editor view without copying access credentials into tool settings.
pub(crate) fn tool_alias_view(content: &str, tool: &str) -> Result<String, String> {
    validate_tool(tool)?;
    let mut document = parse_document(content)?;
    let scope = document.get("tool-model-aliases").and_then(|value| value.get(tool)).cloned();
    // Existing desktop mappings remain usable until the first scoped edit.
    if scope.is_none() && tool == "claude-desktop" { return Ok(content.to_string()); }
    let root = document.as_mapping_mut().ok_or("Configuration must be an object")?;
    for (section, _) in V8_PROVIDER_FAMILIES {
        if let Some(providers) = root.get_mut(yaml_key(section)).and_then(serde_norway::Value::as_sequence_mut) {
            for provider in providers {
                if let Some(models) = provider.get_mut("models").and_then(serde_norway::Value::as_sequence_mut) {
                    for model in models {
                        if let Some(model) = model.as_mapping_mut() {
                            model.remove(yaml_key("alias"));
                        }
                    }
                }
            }
        }
    }
    root.remove(yaml_key("oauth-model-alias"));
    // Alias payload rules are written and evaluated within the selected tool.
    root.remove(yaml_key("payload"));
    if let Some(scope) = scope {
        if let Some(providers) = scope.get("providers").and_then(serde_norway::Value::as_sequence) {
            for entry in providers {
                let Some(section) = entry.get("section").and_then(serde_norway::Value::as_str) else { continue; };
                let Some((legacy, _)) = V8_PROVIDER_FAMILIES.iter().find(|(_, native)| *native == section) else { continue; };
                let Some(identity) = entry.get("identity").and_then(serde_norway::Value::as_str) else { continue; };
                if let Some(provider) = root.get_mut(yaml_key(legacy))
                    .and_then(serde_norway::Value::as_sequence_mut)
                    .and_then(|items| items.iter_mut().find(|provider| provider_identity(section, provider) == identity))
                    .and_then(serde_norway::Value::as_mapping_mut) {
                    if let Some(models) = entry.get("models") {
                        provider.insert(yaml_key("models"), models.clone());
                    }
                }
            }
        }
        for key in ["oauth-model-alias", "payload"] {
            if let Some(value) = scope.get(key) { root.insert(yaml_key(key), value.clone()); }
        }
    }
    serde_norway::to_string(&document).map_err(|error| error.to_string())
}

pub(crate) fn tool_alias_value(content: &str) -> Result<serde_norway::Value, String> {
    let document = parse_document(content)?;
    let mut providers = Vec::new();
    for (legacy, native) in V8_PROVIDER_FAMILIES {
        if let Some(entries) = document.get(legacy).and_then(serde_norway::Value::as_sequence) {
            for (index, provider) in entries.iter().enumerate() {
                if let Some(models) = provider.get("models") {
                    providers.push(serde_json::json!({"section": native, "index": index,
                        "identity": provider_identity(native, provider), "models": models}));
                }
            }
        }
    }
    serde_norway::to_value(serde_json::json!({
        "providers": providers,
        "oauth-model-alias": document.get("oauth-model-alias"),
        "payload": document.get("payload"),
    })).map_err(|error| error.to_string())
}

pub(crate) async fn fetch_tool_alias_yaml(config: &GuiConfigFile, tool: &str) -> Result<String, String> {
    tool_alias_view(&fetch_management_config_yaml(config).await?, tool)
}

pub(crate) async fn save_tool_alias_yaml(config: &GuiConfigFile, tool: &str, content: &str) -> Result<(), String> {
    validate_tool(tool)?;
    put_management_config_value(config, &format!("tool-model-aliases/{tool}"), &tool_alias_value(content)?).await
}

pub(crate) async fn commit_tool_alias_yaml<T>(config: &GuiConfigFile, tool: &str, before: &str, after: &str,
    commit: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    static SAVE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
    let _guard = SAVE_LOCK.lock().await;
    validate_tool(tool)?;
    let raw = fetch_management_config_yaml(config).await?;
    let original = parse_document(&raw)?.get("tool-model-aliases").and_then(|value| value.get(tool)).cloned();
    let latest = fetch_tool_alias_yaml(config, tool).await?;
    if tool_alias_value(before)? != tool_alias_value(&latest)? {
        return Err("Tool aliases changed. Refresh and retry".into());
    }
    let updated = tool_alias_value(after)?;
    let result = async {
        save_tool_alias_yaml(config, tool, after).await?;
        let saved = parse_document(&fetch_management_config_yaml(config).await?)?;
        if saved.get("tool-model-aliases").and_then(|value| value.get(tool)) != Some(&updated) {
            return Err("Tool alias write could not be verified".into());
        }
        commit()
    }.await;
    match result {
        Ok(value) => Ok(value),
        Err(error) => {
            let current = parse_document(&fetch_management_config_yaml(config).await?)?;
            let current = current.get("tool-model-aliases").and_then(|value| value.get(tool));
            if current != Some(&updated) && current != original.as_ref() {
                return Err(format!("{error}; tool aliases changed externally and were not overwritten"));
            }
            let restore = if let Some(original) = original {
                put_management_config_value(config, &format!("tool-model-aliases/{tool}"), &original).await
            } else {
                let response = management_http_client()?.post(management_endpoint(config, &format!("config/tool-model-aliases/{tool}"))?)
                    .header("Authorization", management_authorization(config)?)
                    .json(&serde_json::json!({"operation": "DELETE"})).send().await.map_err(|error| error.to_string())?;
                read_management_value(response).await.map(|_| ())
            };
            restore.map_err(|restore| format!("{error}; rollback failed: {restore}"))?;
            Err(format!("Original configuration was restored: {error}"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scoped_aliases_keep_originals_and_do_not_copy_credentials() {
        let input = "codex-api-key:\n- api-key: secret\n  models: [{name: deepseek-flash, alias: claude-sonnet-4-5}]\n";
        let codex = tool_alias_view(input, "codex").unwrap();
        assert!(!codex.contains("alias: claude"));
        assert!(codex.contains("deepseek-flash"));
        let desktop = tool_alias_view(input, "claude-desktop").unwrap();
        let stored = tool_alias_value(&desktop).unwrap();
        assert!(!serde_norway::to_string(&stored).unwrap().contains("secret"));
        let mut document = parse_document(input).unwrap();
        document.as_mapping_mut().unwrap().insert(yaml_key("tool-model-aliases"),
            serde_norway::to_value(serde_json::json!({"claude-desktop": stored})).unwrap());
        let content = serde_norway::to_string(&document).unwrap();
        assert!(tool_alias_view(&content, "claude-desktop").unwrap().contains("alias: claude-sonnet"));
        let view = parse_document(&tool_alias_view(&content, "codex").unwrap()).unwrap();
        assert!(view["codex-api-key"][0]["models"][0].get("alias").is_none());
    }

    #[tokio::test(flavor = "current_thread")]
    async fn scoped_writes_preserve_other_tools_and_rollback_failed_commits() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        for failure in 0..3 {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let port = listener.local_addr().unwrap().port();
            listener.set_nonblocking(true).unwrap();
            let before = "codex-api-key:\n- api-key: fixture\n  models: [{name: deepseek-flash, alias: original}]\n";
            let after = before.replace("alias: original", "alias: updated");
            let initial = serde_json::json!({"codex-api-key": [{"api-key": "fixture", "models": [{"name": "deepseek-flash"}]}],
                "tool-model-aliases": {"codex": tool_alias_value(before).unwrap(), "opencode": {"providers": []}}});
            let initial_server = initial.clone();
            let server = std::thread::spawn(move || {
                let mut persisted = initial_server;
                let mut writes = 0;
                let mut requests = 0;
                let expected = [4, 6, 5][failure];
                let deadline = std::time::Instant::now() + Duration::from_secs(15);
                while requests < expected && std::time::Instant::now() < deadline {
                    let Ok((mut socket, _)) = listener.accept() else {
                        std::thread::sleep(Duration::from_millis(5));
                        continue;
                    };
                    socket.set_nonblocking(false).unwrap();
                    socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                    let mut bytes = Vec::new();
                    let end = loop {
                        let mut buffer = [0; 4096];
                        let count = socket.read(&mut buffer).unwrap();
                        assert!(count > 0);
                        bytes.extend_from_slice(&buffer[..count]);
                        if let Some(end) = bytes.windows(4).position(|value| value == b"\r\n\r\n") { break end + 4; }
                    };
                    let header = String::from_utf8(bytes[..end].to_vec()).unwrap();
                    let length = header.lines().filter_map(|line| line.split_once(':'))
                        .find(|(key, _)| key.eq_ignore_ascii_case("content-length"))
                        .map(|(_, value)| value.trim().parse::<usize>().unwrap()).unwrap_or(0);
                    while bytes.len() < end + length {
                        let mut buffer = [0; 4096];
                        let count = socket.read(&mut buffer).unwrap();
                        assert!(count > 0);
                        bytes.extend_from_slice(&buffer[..count]);
                    }
                    requests += 1;
                    let mut status = "200 OK";
                    let response = if header.starts_with("GET /v8/management/config.yaml ") {
                        serde_norway::to_string(&persisted).unwrap()
                    } else {
                        assert!(header.starts_with("POST /v8/management/config/tool-model-aliases/codex "));
                        let body: serde_json::Value = serde_json::from_slice(&bytes[end..end + length]).unwrap();
                        assert_eq!(body["operation"], "PUT");
                        persisted["tool-model-aliases"]["codex"] = body["value"].clone();
                        writes += 1;
                        if failure == 2 && writes == 1 { status = "500 Internal Server Error"; }
                        "{}".to_string()
                    };
                    write!(socket, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}", response.len()).unwrap();
                }
                assert_eq!(requests, expected);
                persisted
            });
            let config = GuiConfigFile { port, management_secret_key: "fixture".into(), ..GuiConfigFile::default() };
            let result = commit_tool_alias_yaml(&config, "codex", before, &after, || {
                if failure == 1 { Err("Local configuration write failed".into()) } else { Ok(()) }
            }).await;
            let persisted = server.join().unwrap();
            assert_eq!(persisted["tool-model-aliases"]["opencode"], initial["tool-model-aliases"]["opencode"]);
            assert_eq!(persisted["codex-api-key"], initial["codex-api-key"]);
            if failure == 0 {
                result.unwrap();
                assert_eq!(persisted["tool-model-aliases"]["codex"], serde_json::to_value(tool_alias_value(&after).unwrap()).unwrap());
            } else {
                assert!(result.unwrap_err().contains("Original configuration was restored"));
                assert_eq!(persisted, initial);
            }
        }
    }
}
