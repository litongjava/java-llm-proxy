package nexus.io.llm.proxy.handler;

import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.NativeEndpointService;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class VideoContentHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    return Aop.get(NativeEndpointService.class).retrieve(request, "/content");
  }
}
