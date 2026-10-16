package nexus.io.llm.proxy.service;

import static org.junit.Assert.*;
import com.jfinal.kit.Kv;
import nexus.io.model.exception.BusinessException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class ZCodeTransportServiceTest {
  @Test
  public void preservesCallerSystemAndAllowsOnlyExplicitPairedCaptchaHeaders() {
    ZCodeTransportService service = new ZCodeTransportService();
    Kv body = Kv.create().set("system", "Caller instructions");
    service.payload(body, "/messages");
    assertEquals("Caller instructions", body.getStr("system"));
    Request.Builder request = new Request.Builder().url("https://example.invalid/messages")
        .header("x-aliyun-captcha-verify-param", "user-proof");
    try {
      service.headers(Kv.create().set("access_token", "fake"), request);
      fail("Region is required with a proof");
    } catch (BusinessException expected) {
      assertEquals("ZCODE_CAPTCHA_INPUT", expected.getCode());
    }
    request.header("x-aliyun-captcha-verify-region", "cn");
    service.headers(Kv.create().set("access_token", "fake"), request);
    assertEquals("user-proof", request.build().header("x-aliyun-captcha-verify-param"));
  }

  @Test
  public void challengeAndAccessRestrictionStopWithoutAutomaticRetry() throws Exception {
    for (int code : new int[]{3007, 3012}) {
      Response response = new Response.Builder().request(new Request.Builder().url("https://example.invalid/messages").build())
          .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden").header("Content-Type", "application/json")
          .body(ResponseBody.create("{\"code\":" + code + "}", MediaType.get("application/json"))).build();
      try {
        new ZCodeTransportService().response(response, true);
        fail("Provider challenge must be explicit");
      } catch (BusinessException expected) {
        assertEquals(code == 3007 ? "ZCODE_CAPTCHA_REQUIRED" : "ZCODE_ACCESS_RESTRICTED", expected.getCode());
      }
    }
  }
}
