package nexus.io.llm.proxy.model;

import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.http.common.HttpRequest;
import okhttp3.WebSocket;
import com.jfinal.kit.Kv;
import java.io.ByteArrayOutputStream;

public class ProxyWebSocketSession {
  public ChannelContext channel;
  public HttpRequest request;
  public volatile WebSocket upstream;
  public Provider provider;
  public String requestedModel;
  public RequestRecord record;
  public final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  public Kv upstreamRequestHeaders = Kv.create();
  public Kv upstreamResponseHeaders = Kv.create();
  public volatile boolean closed;
}
