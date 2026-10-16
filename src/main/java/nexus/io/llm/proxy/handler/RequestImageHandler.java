package nexus.io.llm.proxy.handler;

import java.nio.file.NoSuchFileException;
import nexus.io.jfinal.aop.Aop;
import nexus.io.llm.proxy.service.RequestImageService;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;

public class RequestImageHandler {
  public HttpResponse handle(HttpRequest request) throws Exception {
    HttpResponse response = TioRequestContext.getResponse();
    String path = request.getParam("path");
    if (!RequestImageService.isImagePath(path)) {
      response.setStatus(400);
      return response.respond(RespBodyVo.fail(400, "Invalid image path"));
    }
    try {
      byte[] bytes = Aop.get(RequestImageService.class).read(path);
      String extension = path.substring(path.lastIndexOf('.') + 1);
      response.addHeader("Content-Type", "image/" + (extension.equals("jpg") ? "jpeg" : extension));
      response.addHeader("X-Content-Type-Options", "nosniff");
      response.setBody(bytes);
      return response;
    } catch (NoSuchFileException missing) {
      response.setStatus(404);
      return response.respond(RespBodyVo.fail(404, "Image not found"));
    }
  }
}
