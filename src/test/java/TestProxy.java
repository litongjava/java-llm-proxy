import com.litongjava.tio.http.client.SimpleTioHttpClient;
import com.litongjava.tio.http.client.packet.HttpResponsePacket;
import com.litongjava.tio.proxy.ProxyInfo;

public class TestProxy {
  public static void main(String[] args) throws Exception {
    // 代理
    String proxyHost = "127.0.0.1";
    int proxyPort = 10808;

    ProxyInfo pi = new ProxyInfo(proxyHost,proxyPort);
    SimpleTioHttpClient client = new SimpleTioHttpClient(pi, 5);

    // 测 http
//    HttpResponsePacket r1 = client.get("http://www.google.com/", 10);
//    System.out.println(r1.statusLine);
//    System.out.println(new String(r1.body, java.nio.charset.StandardCharsets.UTF_8));

    // 测 https（会走 CONNECT + TLS）
    HttpResponsePacket r2 = client.get("https://www.google.com/", 30);
    System.out.println(r2.statusLine);
    System.out.println(new String(r2.body, java.nio.charset.StandardCharsets.UTF_8));
  }
}
