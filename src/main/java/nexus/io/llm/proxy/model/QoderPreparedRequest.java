package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;

public record QoderPreparedRequest(String url, String body, Kv headers) { }
