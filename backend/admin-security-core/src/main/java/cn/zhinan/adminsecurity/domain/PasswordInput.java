package cn.zhinan.adminsecurity.domain;

/** Login/hash resource bounds, NOT the account-creation password policy. No normalization. */
public final class PasswordInput {
  private PasswordInput() {}

  public static boolean isBounded(char[] value) {
    if (value == null || value.length == 0 || value.length > 256) return false;
    int points = 0;
    int bytes = 0;
    for (int i = 0; i < value.length; i++) {
      char c = value[i];
      if (Character.isHighSurrogate(c)) {
        if (++i == value.length || !Character.isLowSurrogate(value[i])) return false;
        bytes += 4;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      } else {
        bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
      }
      if (++points > 128 || bytes > 512) return false;
    }
    return true;
  }
}
