package com.hookrelay.auth;

public final class TenantContext {
  private static final ThreadLocal<ProjectIdentity> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(ProjectIdentity identity) {
    CURRENT.set(identity);
  }

  public static ProjectIdentity require() {
    ProjectIdentity identity = CURRENT.get();
    if (identity == null) {
      throw new AuthenticationRequiredException("Tenant context is not available for this request");
    }
    return identity;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
