package cn.zhinan.adminsecurity.domain;

/** Capability checks do not replace a business module's resource and state checks. */
public final class AdminAccessPolicy {
  public boolean allows(AdminPrincipal principal, AdminPermission permission) {
    return principal != null && permission != null && principal.permissions().contains(permission);
  }
}
