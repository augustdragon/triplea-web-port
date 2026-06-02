package org.triplea.web.controlplane.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigAllowListTest {

  private final AllowList allowList = new ConfigAllowList(Set.of("google:alice", "discord:bob"));

  @Test
  void allowsListedIdentities() {
    assertThat(allowList.isAllowed("google", "alice"), is(true));
    assertThat(allowList.isAllowed("discord", "bob"), is(true));
  }

  @Test
  void rejectsUnlistedSubjectOrProvider() {
    assertThat(allowList.isAllowed("google", "bob"), is(false)); // right provider, wrong subject
    assertThat(allowList.isAllowed("discord", "alice"), is(false)); // crossed pairing
    assertThat(allowList.isAllowed("github", "alice"), is(false)); // unknown provider
  }
}
