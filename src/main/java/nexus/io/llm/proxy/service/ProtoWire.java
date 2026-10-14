package nexus.io.llm.proxy.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ProtoWire {
  public record Field(int number, int type, long numberValue, byte[] bytes) {
    public String text() { return new String(bytes, StandardCharsets.UTF_8); }
  }
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  public ProtoWire number(int field, long value) { varint(((long) field << 3)); varint(value); return this; }
  public ProtoWire text(int field, String value) { return bytes(field, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
  public ProtoWire bytes(int field, byte[] value) { varint(((long) field << 3) | 2); varint(value.length); output.writeBytes(value); return this; }
  public ProtoWire decimal(int field, double value) { varint(((long) field << 3) | 1); output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()); return this; }
  public byte[] bytes() { return output.toByteArray(); }
  private void varint(long value) {
    while ((value & ~127L) != 0) { output.write((int) (value & 127) | 128); value >>>= 7; }
    output.write((int) value);
  }
  public static List<Field> parse(byte[] bytes) throws IOException {
    ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    List<Field> result = new ArrayList<>();
    while (input.hasRemaining()) {
      long tag = readVarint(input);
      int type = (int) (tag & 7);
      int number = (int) (tag >>> 3);
      if (number == 0) { throw new IOException("Invalid protobuf field"); }
      long value = 0;
      byte[] data = new byte[0];
      if (type == 0) { value = readVarint(input); }
      else if (type == 1 && input.remaining() >= 8) { value = input.getLong(); }
      else if (type == 5 && input.remaining() >= 4) { value = input.getInt(); }
      else if (type == 2) {
        long length = readVarint(input);
        if (length < 0 || length > input.remaining()) { throw new IOException("Invalid protobuf length"); }
        data = new byte[(int) length]; input.get(data);
      } else { throw new IOException("Invalid protobuf wire type or truncated field"); }
      result.add(new Field(number, type, value, data));
    }
    return result;
  }
  private static long readVarint(ByteBuffer input) throws IOException {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (!input.hasRemaining()) { throw new IOException("Truncated protobuf varint"); }
      int item = input.get() & 255;
      value |= (long) (item & 127) << shift;
      if ((item & 128) == 0) { return value; }
    }
    throw new IOException("Invalid protobuf varint");
  }
}
