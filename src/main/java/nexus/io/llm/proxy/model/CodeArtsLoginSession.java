package nexus.io.llm.proxy.model;

import java.util.concurrent.atomic.AtomicBoolean;
import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

/** Internal authorization state, never returned directly by a management handler. */
public class CodeArtsLoginSession implements AutoCloseable {
  public String authUrl;
  public String redirectUri;
  public String ticketId;
  public String verifier;
  public Kv privateJwk;
  public long expiresAt;
  public volatile Kv credential;
  public volatile String error;
  public volatile boolean cancelled;
  public volatile Thread ticketWorker;
  public HttpServer server;
  public final AtomicBoolean exchanging = new AtomicBoolean();
  @Override public synchronized void close() {
    cancelled = true;
    verifier = null;
    privateJwk = null;
    if (ticketWorker != null && ticketWorker != Thread.currentThread()) { ticketWorker.interrupt(); }
    if (server != null) { server.stop(0); }
  }
}
