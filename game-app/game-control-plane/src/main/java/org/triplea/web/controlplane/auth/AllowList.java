package org.triplea.web.controlplane.auth;

/**
 * Access control v1: only invited identities may log in or hold a session. Checked both at login
 * (don't mint a cookie for an uninvited identity) and on every request (so removing an entry
 * revokes access within the JWT's short TTL). The config-backed implementation is {@link
 * ConfigAllowList}; a DB-backed one can replace it behind this seam later.
 */
public interface AllowList {

  /** True if {@code (provider, subject)} is on the invite list. */
  boolean isAllowed(String provider, String subject);
}
