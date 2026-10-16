package nexus.io.llm.proxy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.websocket.common.WebSocketResponse;
import okhttp3.WebSocket;

public class LiveCallSession {
  public String id;
  public String owner;
  public String tool;
  public String model;
  public String providerId;
  public long expires;
  public ChannelContext channel;
  public WebSocket upstream;
  public boolean ready;
  public boolean closed;
  public int pendingBytes;
  public final List<WebSocketResponse> pending = new ArrayList<>();
  public CompletableFuture<Integer> handshake = new CompletableFuture<>();
}
