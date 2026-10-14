package nexus.io.llm.proxy.model;

import com.jfinal.kit.Kv;
import lombok.Data;
import java.util.List;
import java.math.BigInteger;

@Data
public class CredentialInfo {
  public String id;
  public String auth_index;
  public String name;
  public String type;
  public String provider;
  public String email;
  public String note;
  public String prefix;
  public String status;
  public String status_message;
  public String source = "file";
  public boolean disabled;
  public boolean unavailable;
  public boolean runtime_only;
  public long size;
  public String created_at;
  public String updated_at;
  public String last_refresh;
  public BigInteger success = BigInteger.ZERO;
  public BigInteger failed = BigInteger.ZERO;
  public List<RecentRequestBucket> recent_requests = List.of();
  public Kv metadata = Kv.create();
}
