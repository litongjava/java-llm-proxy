package nexus.io.llm.proxy.model;

import java.math.BigInteger;
import lombok.Data;

@Data
public class RecentRequestBucket {
  public String time;
  public BigInteger success = BigInteger.ZERO;
  public BigInteger failed = BigInteger.ZERO;
}
