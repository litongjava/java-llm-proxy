package nexus.io.llm.proxy.service;

import java.util.Arrays;
import java.util.List;
import com.jfinal.kit.Kv;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.exception.BusinessException;
import nexus.io.tio.utils.environment.EnvUtils;

public class PluginCommandService {
  public Integer execute(String[] args) throws Exception {
    Integer exit = null;
    for (NativePlugin plugin : Aop.get(PluginService.class).enabled("command_line_plugin")) {
      Kv registration;
      try { registration = plugin.call("command_line.register", Kv.create().set("Plugin", plugin.registration.get("metadata"))); }
      catch (Exception error) {
        if (Arrays.stream(args).anyMatch(argument -> argument.replaceFirst("^-{1,2}", "").startsWith("plugin." + plugin.id + "."))) { throw error; }
        System.err.println("Cannot register command flags for plugin " + plugin.id + ": " + error.getClass().getSimpleName());
        continue;
      }
      Kv flags = Kv.create();
      Kv triggered = Kv.create();
      for (Object value : ProxyJson.list(registration.get("Flags"))) {
        Kv flag = ProxyJson.object(value);
        String name = flag.getStr("Name");
        String type = ProxyJson.text(flag, "Type", "string");
        String actual = ProxyJson.text(flag, "DefaultValue", "");
        boolean supplied = false;
        for (int index = 0; index < args.length; index++) {
          String argument = args[index].replaceFirst("^-{1,2}", "");
          String prefix = "plugin." + plugin.id + ".";
          if (argument.startsWith(prefix)) { argument = argument.substring(prefix.length()); }
          if (argument.startsWith(name + "=")) { actual = argument.substring(name.length() + 1); supplied = true; }
          else if (argument.equals(name)) {
            supplied = true;
            if ("bool".equals(type)) { actual = "true"; }
            else { BusinessException.require(index + 1 < args.length, 400, "PLUGIN_FLAG", "Missing plugin flag value: " + name); actual = args[index + 1]; }
          }
        }
        if (supplied) { validate(type, actual, name); }
        Kv parsed = Kv.create().set("Name", name).set("Type", type).set("Value", actual).set("Set", supplied);
        flags.set(name, parsed);
        if (supplied) { triggered.set(name, parsed); }
      }
      if (triggered.isEmpty()) { continue; }
      Kv result = plugin.call("command_line.execute", Kv.create().set("Plugin", plugin.registration.get("metadata"))
          .set("Program", "java-llm-proxy").set("Args", Arrays.asList(args)).set("ConfigPath", EnvUtils.get("proxy.config", "data/config.yaml"))
          .set("Host", Kv.create()).set("Flags", flags).set("TriggeredFlags", triggered));
      System.out.print(PluginHostCallbacks.text(result.get("Stdout")));
      System.err.print(PluginHostCallbacks.text(result.get("Stderr")));
      for (Object auth : ProxyJson.list(result.get("Auths"))) { Aop.get(OAuthService.class).savePluginAuth(ProxyJson.object(auth)); }
      exit = (int) ProxyJson.number(result, "ExitCode", 0);
      if (exit != 0) { return exit; }
    }
    return exit;
  }

  private void validate(String type, String value, String name) {
    boolean valid;
    try {
      valid = switch (type) {
        case "bool" -> List.of("true", "false", "1", "0").contains(value);
        case "int", "int64" -> { Long.parseLong(value); yield true; }
        case "float64" -> Double.isFinite(Double.parseDouble(value));
        case "duration" -> value.matches("(?:[0-9]+(?:\\.[0-9]+)?(?:ns|us|ms|s|m|h))+");
        default -> true;
      };
    } catch (NumberFormatException error) { valid = false; }
    BusinessException.require(valid, 400, "PLUGIN_FLAG", "Invalid plugin flag: " + name);
  }
}
