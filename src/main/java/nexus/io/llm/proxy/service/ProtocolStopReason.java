package nexus.io.llm.proxy.service;

import com.jfinal.kit.Kv;

/** Shared terminal semantics for buffered and streaming protocol translation. */
final class ProtocolStopReason {
  private ProtocolStopReason() {}
  static String chat(String reason) {
    if (reason == null) { return "stop"; }
    return switch (reason) {
      case "tool_use", "tool_calls" -> "tool_calls";
      case "max_tokens", "length" -> "length";
      case "refusal", "sensitive", "content_filter" -> "content_filter";
      default -> reason.equals("model_context_window_exceeded") ? reason : "stop";
    };
  }
  static String anthropic(String reason, boolean tools) {
    return switch (chat(reason)) {
      case "length" -> "max_tokens";
      case "content_filter" -> "refusal";
      case "model_context_window_exceeded" -> "model_context_window_exceeded";
      default -> tools ? "tool_use" : "end_turn";
    };
  }
  static Kv response(Kv response, String reason) {
    String mapped = chat(reason);
    if ("model_context_window_exceeded".equals(mapped)) {
      response.set("status", "failed").set("error", Kv.create().set("code", "context_length_exceeded")
          .set("message", "Upstream stopped because the model context window was exceeded."));
    } else if ("length".equals(mapped) || "content_filter".equals(mapped)) {
      response.set("status", "incomplete").set("incomplete_details", Kv.create()
          .set("reason", "length".equals(mapped) ? "max_output_tokens" : "content_filter"));
    }
    return response;
  }
}
