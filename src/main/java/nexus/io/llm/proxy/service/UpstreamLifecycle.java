package nexus.io.llm.proxy.service;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import nexus.io.tio.core.ChannelContext;
import nexus.io.tio.utils.environment.EnvUtils;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cancels abandoned or silent HTTP calls without limiting healthy stream duration. */
final class UpstreamLifecycle implements Interceptor {
  private static final Logger LOG = LoggerFactory.getLogger(UpstreamLifecycle.class);
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
    Thread thread = new Thread(task, "upstream-lifecycle");
    thread.setDaemon(true);
    return thread;
  });
  static { TIMER.setRemoveOnCancelPolicy(true); }
  private final BooleanSupplier disconnected;
  private final long headerNanos;
  private final long idleNanos;
  private final String requestId;

  UpstreamLifecycle(BooleanSupplier disconnected, long headerMillis, long idleMillis, String requestId) {
    this.disconnected = disconnected;
    headerNanos = TimeUnit.MILLISECONDS.toNanos(headerMillis);
    idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMillis);
    this.requestId = requestId;
  }

  static boolean disconnected(ChannelContext channel) {
    return channel != null && (channel.isClosed || channel.isRemoved);
  }

  static OkHttpClient client(OkHttpClient base, ChannelContext channel, String requestId) {
    return base.newBuilder().addInterceptor(new UpstreamLifecycle(() -> disconnected(channel),
        Math.max(1, EnvUtils.getInt("proxy.upstream.header-timeout-seconds", 180)) * 1000L,
        Math.max(1, EnvUtils.getInt("proxy.upstream.idle-timeout-seconds", 300)) * 1000L, requestId)).build();
  }

  @Override public Response intercept(Chain chain) throws IOException {
    if (disconnected.getAsBoolean()) { throw new IOException("Client disconnected"); }
    Watch watch = new Watch();
    LOG.info("Upstream request {} started", requestId);
    ScheduledFuture<?> timer = TIMER.scheduleAtFixedRate(() -> {
      synchronized (watch) {
        if (watch.done) { return; }
        String reason = disconnected.getAsBoolean() ? "Client disconnected" :
            System.nanoTime() - watch.activity >= (watch.headers ? idleNanos : headerNanos)
                ? (watch.headers ? "Upstream idle timeout" : "Upstream response header timeout") : null;
        if (reason != null) {
          watch.done = true;
          LOG.warn("Cancelling request {}: {}", requestId, reason);
          chain.call().cancel();
        }
      }
    }, 100, 100, TimeUnit.MILLISECONDS);
    try {
      Response response = chain.proceed(chain.request());
      synchronized (watch) { watch.headers = true; watch.activity = System.nanoTime(); }
      LOG.info("Upstream request {} received headers (status={})", requestId, response.code());
      ResponseBody body = response.body();
      if (body == null) { watch.finish(timer); return response; }
      BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
        @Override public long read(Buffer sink, long count) throws IOException {
          try {
            long read = super.read(sink, count);
            if (read == -1) { watch.finish(timer); }
            else if (read > 0) { synchronized (watch) { watch.activity = System.nanoTime(); } }
            return read;
          } catch (IOException error) { watch.finish(timer); throw error; }
        }
        @Override public void close() throws IOException {
          watch.finish(timer);
          super.close();
        }
      });
      return response.newBuilder().body(new ResponseBody() {
        public MediaType contentType() { return body.contentType(); }
        public long contentLength() { return body.contentLength(); }
        public BufferedSource source() { return source; }
      }).build();
    } catch (IOException | RuntimeException error) {
      watch.finish(timer);
      throw error;
    }
  }

  private static final class Watch {
    long activity = System.nanoTime();
    boolean headers;
    boolean done;
    synchronized void finish(ScheduledFuture<?> timer) { done = true; timer.cancel(false); }
  }
}
