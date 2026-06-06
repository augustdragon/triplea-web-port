package org.triplea.web.controlplane.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigAllowListTest {

  private final AllowList allowList =
      new ConfigAllowList(Set.of("google:alice", "discord:bob", "google:carol@example.com"));

  private static Identity id(final String provider, final String subject, final String email) {
    return new Identity(provider, subject, "Name", email);
  }

  @Test
  void allowsListedIdentitiesBySubject() {
    assertThat(allowList.isAllowed(id("google", "alice", null)), is(true));
    assertThat(allowList.isAllowed(id("discord", "bob", null)), is(true));
  }

  @Test
  void allowsListedIdentityByEmailWhenSubjectIsOpaque() {
    // Real OAuth gives an opaque subject we can't pre-invite; the email match covers it.
    assertThat(
        allowList.isAllowed(id("google", "117-opaque-subject", "carol@example.com")), is(true));
  }

  @Test
  void rejectsUnlistedSubjectProviderOrEmail() {
    assertThat(allowList.isAllowed(id("google", "bob", null)), is(false)); // wrong subject
    assertThat(allowList.isAllowed(id("discord", "alice", null)), is(false)); // crossed pairing
    assertThat(allowList.isAllowed(id("github", "alice", null)), is(false)); // unknown provider
    assertThat(
        allowList.isAllowed(id("google", "x", "dave@example.com")), is(false)); // email not listed
    assertThat(
        allowList.isAllowed(id("discord", "x", "carol@example.com")),
        is(false)); // email listed only for google
  }
}
