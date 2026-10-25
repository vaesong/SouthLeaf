package cn.zhinan.adminsecurity.domain;

public record PasswordMatch(boolean matches, boolean upgradeRequired) {
  public PasswordMatch {
    if (!matches && upgradeRequired)
      throw new IllegalArgumentException("Invalid verification result");
  }
}
