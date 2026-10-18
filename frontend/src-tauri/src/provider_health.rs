use super::{
    build_http_client_with_proxy, effective_agent_api_key, fetch_agent_model_payload, is_loopback_host,
    managed_core_loopback_origin, managed_core_tls_enabled, usage, validate_agent_model,
    GuiConfigFile, GuiConfigState, APP_USER_AGENT,
};
use chrono::Local;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    time::{Duration, Instant},
};

const MAX_PROVIDER_HEALTH_STREAM_BYTES: usize = 256 * 1024;
static PROVIDER_HEALTH_SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(4);

#[cfg(test)]
mod tests;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProviderHealthProbeRequest {
    url: String,
    header: HashMap<String, String>,
    data: String,
    protocol: String,
    #[serde(default)]
    source_provider: Option<String>,
    #[serde(default)]
    proxy_url: Option<String>,
    timeout_ms: Option<u64>,
    #[serde(default)]
    model: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    auth_index: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProviderHealthProbeResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    first_token_latency_ms: Option<u64>,
    response_latency_ms: u64,
}

#[derive(Default)]
struct ProviderHealthUsageTokens {
    input_tokens: u64,
    output_tokens: u64,
    reasoning_tokens: u64,
    cache_read_tokens: u64,
    total_tokens: u64,
}

fn provider_health_value_has_text(value: Option<&serde_json::Value>) -> bool {
    match value {
        Some(serde_json::Value::String(text)) => !text.trim().is_empty(),
        Some(serde_json::Value::Array(items)) => items
            .iter()
            .any(|item| provider_health_value_has_text(Some(item))),
        Some(serde_json::Value::Object(object)) => ["text", "content"]
            .iter()
            .any(|key| provider_health_value_has_text(object.get(*key))),
        _ => false,
    }
}

fn provider_health_json_has_text(protocol: &str, value: &serde_json::Value) -> bool {
    match protocol {
        "openai-chat" => value
            .get("choices")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|choices| {
                choices.iter().any(|choice| {
                    provider_health_value_has_text(choice.pointer("/delta/content"))
                        || provider_health_value_has_text(
                            choice.pointer("/delta/reasoning_content"),
                        )
                        || provider_health_value_has_text(choice.pointer("/delta/reasoning"))
                        || provider_health_value_has_text(choice.pointer("/delta/thinking"))
                        || provider_health_value_has_text(choice.pointer("/message/content"))
                })
            }),
        "openai-responses" => {
            (matches!(
                value.get("type").and_then(serde_json::Value::as_str),
                Some(
                    "response.output_text.delta"
                        | "response.reasoning_text.delta"
                        | "response.reasoning_summary_text.delta"
                )
            ) && provider_health_value_has_text(value.get("delta")))
                || value
                    .get("output")
                    .and_then(serde_json::Value::as_array)
                    .is_some_and(|output| {
                        output.iter().any(|item| {
                            item.get("content")
                                .and_then(serde_json::Value::as_array)
                                .is_some_and(|content| {
                                    content.iter().any(|part| {
                                        provider_health_value_has_text(part.get("text"))
                                    })
                                })
                        })
                    })
        }
        "claude" => {
            provider_health_value_has_text(value.pointer("/delta/text"))
                || provider_health_value_has_text(value.pointer("/delta/thinking"))
                || value
                    .get("content")
                    .and_then(serde_json::Value::as_array)
                    .is_some_and(|content| {
                        content
                            .iter()
                            .any(|part| provider_health_value_has_text(part.get("text")))
                    })
        }
        "gemini" => value
            .get("candidates")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|candidates| {
                candidates.iter().any(|candidate| {
                    candidate
                        .pointer("/content/parts")
                        .and_then(serde_json::Value::as_array)
                        .is_some_and(|parts| {
                            parts
                                .iter()
                                .any(|part| provider_health_value_has_text(part.get("text")))
                        })
                })
            }),
        "interactions" => {
            let event = value.get("event_type").or_else(|| value.get("type")).and_then(serde_json::Value::as_str);
            (event == Some("content.delta")
                && value.pointer("/delta/type").and_then(serde_json::Value::as_str) == Some("text")
                && provider_health_value_has_text(value.pointer("/delta/text")))
                || (event == Some("interaction.complete")
                    && value.pointer("/interaction/status").and_then(serde_json::Value::as_str) == Some("completed")
                    && provider_health_value_has_text(value.pointer("/interaction/outputs")))
        }
        _ => false,
    }
}

pub(crate) fn provider_health_stream_has_text(protocol: &str, bytes: &[u8]) -> bool {
    let text = String::from_utf8_lossy(bytes);
    text.lines().any(|line| {
        let line = line.trim();
        let data = line.strip_prefix("data:").map(str::trim).unwrap_or(line);
        if data.is_empty() || data == "[DONE]" {
            return false;
        }
        serde_json::from_str::<serde_json::Value>(data)
            .ok()
            .is_some_and(|value| provider_health_json_has_text(protocol, &value))
    })
}

fn provider_health_json_has_terminal_success(protocol: &str, value: &serde_json::Value) -> bool {
    if protocol == "interactions" {
        let event = value.get("event_type").or_else(|| value.get("type")).and_then(serde_json::Value::as_str);
        return event == Some("interaction.complete")
            && value.pointer("/interaction/status").and_then(serde_json::Value::as_str) == Some("completed");
    }
    if protocol == "openai-chat" {
        // CPA translates Gemini's exhausted thinking budget into OpenAI chunks.
        // A confirmed reasoning-only generation is usable, but has no text TTFT.
        let exhausted_thinking_budget = value
            .get("choices")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|choices| choices.iter().any(|choice| {
                matches!(choice.get("finish_reason").and_then(serde_json::Value::as_str),
                    Some("length" | "max_tokens"))
            }));
        let thoughts = value.pointer("/usage/completion_tokens_details/reasoning_tokens")
            .and_then(serde_json::Value::as_u64).unwrap_or_default();
        let output = value.pointer("/usage/completion_tokens")
            .and_then(serde_json::Value::as_u64).unwrap_or_default();
        return exhausted_thinking_budget && thoughts > 0 && output >= thoughts;
    }
    if protocol != "gemini" {
        return false;
    }
    let exhausted_thinking_budget = value
        .get("candidates")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|candidates| {
            candidates.iter().any(|candidate| {
                candidate
                    .get("finishReason")
                    .and_then(serde_json::Value::as_str)
                    == Some("MAX_TOKENS")
            })
        });
    let thoughts = value
        .pointer("/usageMetadata/thoughtsTokenCount")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or_default();
    let total = value
        .pointer("/usageMetadata/totalTokenCount")
        .and_then(serde_json::Value::as_u64)
        .unwrap_or_default();
    exhausted_thinking_budget && thoughts > 0 && total >= thoughts
}

pub(crate) fn provider_health_stream_has_terminal_success(protocol: &str, bytes: &[u8]) -> bool {
    let text = String::from_utf8_lossy(bytes);
    text.lines().any(|line| {
        let line = line.trim();
        let data = line.strip_prefix("data:").map(str::trim).unwrap_or(line);
        if data.is_empty() || data == "[DONE]" {
            return false;
        }
        serde_json::from_str::<serde_json::Value>(data)
            .ok()
            .is_some_and(|value| provider_health_json_has_terminal_success(protocol, &value))
    })
}

fn provider_health_usage_tokens(protocol: &str, bytes: &[u8]) -> ProviderHealthUsageTokens {
    let mut tokens = ProviderHealthUsageTokens::default();
    if protocol == "claude" {
        for line in String::from_utf8_lossy(bytes).lines() {
            let Some(data) = line.trim().strip_prefix("data:") else { continue; };
            let Ok(value) = serde_json::from_str::<serde_json::Value>(data.trim()) else { continue; };
            let Some(usage) = value.get("usage").or_else(|| value.pointer("/message/usage")) else { continue; };
            tokens.input_tokens = tokens.input_tokens.max(usage["input_tokens"].as_u64().unwrap_or_default());
            tokens.output_tokens = tokens.output_tokens.max(usage["output_tokens"].as_u64().unwrap_or_default());
            tokens.cache_read_tokens = tokens.cache_read_tokens.max(usage["cache_read_input_tokens"].as_u64().unwrap_or_default());
        }
        tokens.total_tokens = tokens.input_tokens + tokens.output_tokens + tokens.cache_read_tokens;
        return tokens;
    }
    if protocol != "gemini" {
        return tokens;
    }
    let text = String::from_utf8_lossy(bytes);
    for line in text.lines() {
        let line = line.trim();
        let data = line.strip_prefix("data:").map(str::trim).unwrap_or(line);
        let Some(usage) = serde_json::from_str::<serde_json::Value>(data)
            .ok()
            .and_then(|value| value.get("usageMetadata").cloned())
        else {
            continue;
        };
        tokens.input_tokens = tokens.input_tokens.max(
            usage
                .get("promptTokenCount")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or_default(),
        );
        tokens.output_tokens = tokens.output_tokens.max(
            usage
                .get("candidatesTokenCount")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or_default(),
        );
        tokens.reasoning_tokens = tokens.reasoning_tokens.max(
            usage
                .get("thoughtsTokenCount")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or_default(),
        );
        tokens.cache_read_tokens = tokens.cache_read_tokens.max(
            usage
                .get("cachedContentTokenCount")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or_default(),
        );
        tokens.total_tokens = tokens.total_tokens.max(
            usage
                .get("totalTokenCount")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or_default(),
        );
    }
    tokens
}

fn provider_health_usage_provider(protocol: &str) -> &str {
    match protocol {
        "openai-responses" => "codex",
        "openai-chat" => "openai",
        "claude" => "claude",
        "gemini" => "gemini",
        "interactions" => "interactions",
        _ => "unknown",
    }
}

fn provider_health_usage_event(
    request: &ProviderHealthProbeRequest,
    endpoint: &str,
    latency_ms: u64,
    ttft_ms: Option<u64>,
    received: &[u8],
    status: Option<u16>,
    error: Option<&str>,
    response_headers: &serde_json::Value,
) -> serde_json::Value {
    let tokens = provider_health_usage_tokens(&request.protocol, received);
    serde_json::json!({
        "timestamp": Local::now().to_rfc3339(),
        "latency_ms": latency_ms,
        "ttft_ms": ttft_ms,
        "source": request.source.as_str(),
        "auth_index": request.auth_index.as_str(),
        "failed": error.is_some(),
        "failure_status": if error.is_some() { status.unwrap_or(0) } else { 0 },
        "failure_body": error.unwrap_or(""),
        "request_detail": {
            "protocol": request.protocol, "status": status,
            "requestBody": request.data, "upstreamRequestBody": request.data,
            "requestHeaders": health_safe_headers(&request.header),
            "upstreamRequestHeaders": health_safe_headers(&request.header),
            "responseHeaders": response_headers, "upstreamResponseHeaders": response_headers,
            "responseBody": String::from_utf8_lossy(received),
            "upstreamResponseBody": String::from_utf8_lossy(received),
        },
        "provider": request.source_provider.as_deref()
            .filter(|provider| matches!(*provider, "interactions" | "vertex" | "xai" | "meta"))
            .unwrap_or_else(|| provider_health_usage_provider(&request.protocol)),
        "model": request.model.as_str(),
        "executor_type": "DesktopProviderHealthCheck",
        "endpoint": endpoint,
        "generate": false,
        "tokens": {
            "input_tokens": tokens.input_tokens,
            "output_tokens": tokens.output_tokens,
            "reasoning_tokens": tokens.reasoning_tokens,
            "cache_read_tokens": tokens.cache_read_tokens,
            "total_tokens": tokens.total_tokens,
        },
    })
}

fn health_safe_headers(headers: &HashMap<String, String>) -> serde_json::Value {
    // Persist only protocol metadata, never credentials or custom auth headers.
    serde_json::to_value(headers.iter().filter(|(name, _)| matches!(
        name.to_ascii_lowercase().as_str(),
        "content-type" | "accept" | "user-agent" | "anthropic-version" | "anthropic-beta"
    )).collect::<HashMap<_, _>>()).unwrap_or_default()
}

pub(crate) fn provider_health_content_type_is_streaming(content_type: &str) -> bool {
    let content_type = content_type.to_ascii_lowercase();
    content_type.contains("text/event-stream")
        || content_type.contains("application/x-ndjson")
        || content_type.contains("application/json-seq")
}

#[tauri::command]
pub(crate) async fn get_core_models(
    gui_config_state: tauri::State<'_, GuiConfigState>,
) -> Result<Vec<CoreHealthModel>, String> {
    let config = gui_config_state.snapshot()?;
    fetch_core_models(config.port, effective_agent_api_key(&config)).await
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CoreHealthModel {
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    display_name: Option<String>,
}

async fn fetch_core_models(port: u16, api_key: &str) -> Result<Vec<CoreHealthModel>, String> {
    let payload = fetch_agent_model_payload(port, api_key).await?;
    parse_core_models(&payload)
}

fn parse_core_models(payload: &serde_json::Value) -> Result<Vec<CoreHealthModel>, String> {
    let object_models = payload.get("models").and_then(serde_json::Value::as_object).map(|models| {
        models.iter().map(|(id, value)| {
            let mut entry = value.as_object().cloned().unwrap_or_default();
            entry.entry("id").or_insert(serde_json::json!(id));
            serde_json::Value::Object(entry)
        }).collect::<Vec<_>>()
    });
    let entries = payload.as_array()
        .or_else(|| payload.get("data").and_then(serde_json::Value::as_array))
        .or_else(|| payload.get("models").and_then(serde_json::Value::as_array))
        .or(object_models.as_ref())
        .ok_or_else(|| "Local model list response is missing a data array or models catalog".to_string())?;
    let mut seen = HashSet::new();
    let mut models = Vec::new();
    for entry in entries {
        let name = entry.as_str()
            .or_else(|| entry.get("id").and_then(serde_json::Value::as_str))
            .or_else(|| entry.get("name").and_then(serde_json::Value::as_str))
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .ok_or_else(|| "Local model list contains an invalid model ID".to_string())?;
        // Agent configuration may resolve aliases or ignore case. Health checks
        // must use each ID exactly as published by the inference endpoint.
        if !seen.insert(name.to_string()) {
            continue;
        }
        let display_name = ["display_name", "displayName", "name"].into_iter()
            .find_map(|key| entry.get(key).and_then(serde_json::Value::as_str))
            .map(str::trim)
            .filter(|display| !display.is_empty() && *display != name)
            .map(str::to_string);
        models.push(CoreHealthModel { name: name.to_string(), display_name });
    }
    Ok(models)
}

#[tauri::command]
pub(crate) async fn core_health_probe(
    gui_config_state: tauri::State<'_, GuiConfigState>,
    model: String,
    timeout_ms: Option<u64>,
) -> Result<ProviderHealthProbeResponse, String> {
    let config = gui_config_state.snapshot()?;
    probe_core_model(&config, &model, timeout_ms).await
}

async fn probe_core_model(
    config: &GuiConfigFile,
    model: &str,
    timeout_ms: Option<u64>,
) -> Result<ProviderHealthProbeResponse, String> {
    let model = validate_agent_model(model)?;
    if config.port == 0 {
        return Err("Invalid kernel port".to_string());
    }
    let tls_enabled = managed_core_tls_enabled();
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(3))
        .timeout(health_probe_timeout(timeout_ms))
        .danger_accept_invalid_certs(tls_enabled)
        .build()
        .map_err(|error| format!("Failed to create kernel health check client: {error}"))?;
    let request = ProviderHealthProbeRequest {
        url: format!("{}/v1/chat/completions", managed_core_loopback_origin(config.port)),
        header: HashMap::from([
            ("Authorization".to_string(), format!("Bearer {}", effective_agent_api_key(config))),
            ("Content-Type".to_string(), "application/json".to_string()),
            ("Accept".to_string(), "text/event-stream".to_string()),
        ]),
        data: serde_json::json!({
            "model": model,
            "messages": [{ "role": "user", "content": "Reply with OK." }],
            // Reasoning tokens share the completion budget on some providers.
            // Leave room for thinking before the short visible health response.
            "max_tokens": 256,
            "stream": true,
        }).to_string(),
        protocol: "openai-chat".to_string(),
        source_provider: None,
        proxy_url: None,
        timeout_ms,
        model,
        source: String::new(),
        auth_index: String::new(),
    };
    // The kernel records these requests itself. Only direct upstream probes need a
    // desktop usage event, otherwise one health check appears twice in usage.
    execute_health_probe(None, client, request).await
}

fn health_probe_timeout(timeout_ms: Option<u64>) -> Duration {
    Duration::from_millis(timeout_ms.unwrap_or(15_000).clamp(1_000, 120_000))
}

#[tauri::command]
pub(crate) async fn provider_health_probe(
    app: tauri::AppHandle,
    gui_config_state: tauri::State<'_, GuiConfigState>,
    request: ProviderHealthProbeRequest,
) -> Result<ProviderHealthProbeResponse, String> {
    let url = reqwest::Url::parse(request.url.trim())
        .map_err(|error| format!("Invalid health check URL: {error}"))?;
    let config = gui_config_state.snapshot()?;
    let proxy_url = provider_health_proxy_url(&url, request.proxy_url.as_deref(), &config.proxy_url);
    let client = build_http_client_with_proxy(
        reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(health_probe_timeout(request.timeout_ms)),
        proxy_url,
        "Failed to create health check client",
    )?;
    execute_health_probe(Some(&app), client, request).await
}

fn provider_health_proxy_url<'a>(url: &reqwest::Url, requested: Option<&'a str>, global: &'a str) -> &'a str {
    if url.host_str().is_some_and(is_loopback_host) {
        return "";
    }
    let proxy = requested.map(str::trim).filter(|value| !value.is_empty()).unwrap_or(global.trim());
    if proxy.eq_ignore_ascii_case("direct") { "" } else { proxy }
}

async fn execute_health_probe(
    usage_app: Option<&tauri::AppHandle>,
    client: reqwest::Client,
    request: ProviderHealthProbeRequest,
) -> Result<ProviderHealthProbeResponse, String> {
    let url = reqwest::Url::parse(request.url.trim())
        .map_err(|error| format!("Invalid health check URL: {error}"))?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err("Health checks support only HTTP or HTTPS URLs".to_string());
    }
    if !matches!(
        request.protocol.as_str(),
        "openai-chat" | "openai-responses" | "claude" | "gemini" | "interactions"
    ) {
        return Err("Unsupported health check protocol".to_string());
    }
    let endpoint = format!("POST {}", url.path());
    if request.data.len() > 64 * 1024 {
        return Err("Health check request body is too large".to_string());
    }

    let _permit = PROVIDER_HEALTH_SLOTS
        .acquire()
        .await
        .map_err(|error| error.to_string())?;
    let mut headers = reqwest::header::HeaderMap::new();
    for (name, value) in &request.header {
        let name = reqwest::header::HeaderName::from_bytes(name.as_bytes())
            .map_err(|error| format!("Invalid health check request header name: {error}"))?;
        let value = reqwest::header::HeaderValue::from_str(value)
            .map_err(|error| format!("Invalid health check request header value: {error}"))?;
        headers.insert(name, value);
    }
    if !headers.contains_key(reqwest::header::USER_AGENT) {
        headers.insert(
            reqwest::header::USER_AGENT,
            reqwest::header::HeaderValue::from_static(APP_USER_AGENT),
        );
    }

    let started_at = Instant::now();
    let mut received = Vec::new();
    let mut first_token_latency_ms = None;
    let mut response_status = None;
    let mut response_headers = serde_json::json!({});
    let result = async {
    let response = client
        .post(url)
        .headers(headers)
        .body(request.data.clone())
        .send()
        .await
        .map_err(|error| format!("Health check request failed: {error}"))?;
    let status = response.status();
    response_status = Some(status.as_u16());
    response_headers = health_safe_headers(&response.headers().iter()
        .filter_map(|(name, value)| value.to_str().ok().map(|value| (name.to_string(), value.to_string())))
        .collect());
    if !status.is_success() {
        let detail = response.text().await.unwrap_or_default();
        received.extend_from_slice(&detail.as_bytes()[..detail.len().min(MAX_PROVIDER_HEALTH_STREAM_BYTES)]);
        let detail = detail.trim();
        return Err(if detail.is_empty() {
            format!("Upstream returned HTTP {}", status.as_u16())
        } else {
            format!("Upstream returned HTTP {}: {}", status.as_u16(), detail)
        });
    }
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    if !provider_health_content_type_is_streaming(&content_type) {
        let bytes = response.bytes().await.unwrap_or_default();
        received.extend_from_slice(&bytes[..bytes.len().min(MAX_PROVIDER_HEALTH_STREAM_BYTES)]);
        return Err("Upstream did not return a streaming response; time to first token cannot be measured".to_string());
    }

    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("Failed to read health check stream: {error}"))?;
        if received.len().saturating_add(chunk.len()) > MAX_PROVIDER_HEALTH_STREAM_BYTES {
            return Err("Health check stream exceeded the size limit".to_string());
        }
        received.extend_from_slice(&chunk);
        if first_token_latency_ms.is_none() && provider_health_stream_has_text(&request.protocol, &received) {
            first_token_latency_ms = Some(started_at.elapsed().as_millis().max(1) as u64);
        }
    }
    if !provider_health_stream_completed(&request.protocol, &received)
        || (first_token_latency_ms.is_none() && !provider_health_stream_has_terminal_success(&request.protocol, &received)) {
        let reason = health_stream_failure_reason(&request.protocol, &received);
        return Err(reason);
    }
    let elapsed_ms = started_at.elapsed().as_millis().max(1) as u64;
    Ok(ProviderHealthProbeResponse { first_token_latency_ms, response_latency_ms: elapsed_ms })
    }.await;
    if let Some(app) = usage_app {
        let event = provider_health_usage_event(&request, &endpoint,
            started_at.elapsed().as_millis().max(1) as u64, first_token_latency_ms,
            &received, response_status, result.as_ref().err().map(String::as_str), &response_headers);
        if let Err(error) = usage::persist_local_usage_event(app, "desktop_health_check", event) {
            eprintln!("Failed to save desktop health check usage record: {error}");
        }
    }
    result
}

fn provider_health_stream_completed(protocol: &str, bytes: &[u8]) -> bool {
    let mut complete = false;
    for line in String::from_utf8_lossy(bytes).lines() {
        let Some(data) = line.trim().strip_prefix("data:") else { continue; };
        let data = data.trim();
        if data == "[DONE]" && protocol == "openai-chat" { complete = true; continue; }
        let Ok(value) = serde_json::from_str::<serde_json::Value>(data) else { continue; };
        let event = value.get("type").or_else(|| value.get("event_type")).and_then(serde_json::Value::as_str).unwrap_or("");
        if value.get("error").is_some() || matches!(event, "error" | "response.failed" | "response.incomplete") {
            return false;
        }
        complete |= match protocol {
            "openai-chat" => value.get("choices").and_then(serde_json::Value::as_array).is_some_and(|choices|
                choices.iter().any(|choice| matches!(choice.get("finish_reason").and_then(serde_json::Value::as_str), Some("stop" | "length" | "tool_calls" | "function_call")))),
            "openai-responses" => event == "response.completed",
            "claude" => event == "message_stop",
            "gemini" => value.get("candidates").and_then(serde_json::Value::as_array).is_some_and(|items|
                items.iter().any(|item| matches!(item.get("finishReason").and_then(serde_json::Value::as_str), Some("STOP" | "MAX_TOKENS")))),
            "interactions" => provider_health_json_has_terminal_success(protocol, &value),
            _ => false,
        };
    }
    complete
}

fn health_stream_failure_reason(protocol: &str, received: &[u8]) -> String {
    for line in String::from_utf8_lossy(received).lines() {
        let Some(data) = line.trim().strip_prefix("data:") else { continue; };
        let Ok(value) = serde_json::from_str::<serde_json::Value>(data.trim()) else { continue; };
        if let Some(error) = value.get("error") {
            return format!("Upstream stream error: {}", error);
        }
        if protocol == "claude" && value.pointer("/delta/stop_reason").and_then(serde_json::Value::as_str) == Some("max_tokens") {
            return "Health check exhausted max_tokens before returning text; increase the output token budget".to_string();
        }
    }
    "Health check stream ended without a successful completion event".to_string()
}
