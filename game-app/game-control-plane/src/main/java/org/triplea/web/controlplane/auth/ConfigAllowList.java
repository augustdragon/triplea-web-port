package org.triplea.web.controlplane.auth;

import java.util.Set;

/**
 * Allow-list backed by the {@code CONTROL_PLANE_ALLOWLIST} env var, parsed into a set of {@code
 * "provider:subject"} entries. In-memory and immutable for the process lifetime — adding an invitee
 * means redeploying with an updated list (acceptable for a private group; a DB-backed {@link
 * AllowList} can replace this when self-service invites are needed).
 */
public final class ConfigAllowList implements AllowList {

  private final Set<String> entries;

  public ConfigAllowList(final Set<String> entries) {
    this.entries = Set.copyOf(entries);
  }

  @Override
  public boolean isAllowed(final String provider, final String subject) {
    return entries.contains(provider + ":" + subject);
  }
}
