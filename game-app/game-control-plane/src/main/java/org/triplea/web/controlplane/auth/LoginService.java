package org.triplea.web.controlplane.auth;

import java.util.Optional;
import org.triplea.web.controlplane.user.User;
import org.triplea.web.controlplane.user.UserDao;

/**
 * The shared login flow every provider funnels through: check the identity against the allow-list,
 * upsert the user, and mint a session token. Returns empty when the identity is not invited (the
 * caller maps that to 403). Keeping this provider-agnostic is what lets {@code DevLoginController}
 * and the future pac4j OAuth callback share one code path.
 */
public final class LoginService {

  /** The user record plus the freshly minted session token to set as a cookie. */
  public record LoginResult(User user, String token) {}

  private final AllowList allowList;
  private final UserDao userDao;
  private final JwtService jwt;

  public LoginService(final AllowList allowList, final UserDao userDao, final JwtService jwt) {
    this.allowList = allowList;
    this.userDao = userDao;
    this.jwt = jwt;
  }

  /** Log in an authenticated identity, or empty if it is not on the allow-list. */
  public Optional<LoginResult> login(final Identity identity) {
    if (!allowList.isAllowed(identity.provider(), identity.subject())) {
      return Optional.empty();
    }
    final User user = userDao.upsertOnLogin(identity);
    return Optional.of(new LoginResult(user, jwt.mint(identity)));
  }
}
