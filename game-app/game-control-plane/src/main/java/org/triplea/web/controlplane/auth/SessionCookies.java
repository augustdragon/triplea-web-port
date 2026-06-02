package org.triplea.web.controlplane.auth;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

/**
 * Reads and writes the session cookie. Always httpOnly (never readable from JS, per the standards)
 * and SameSite=Lax; marked Secure only in prod (local dev runs over plain HTTP, where a Secure
 * cookie would never be sent). The value is the signed JWT from {@link JwtService}.
 */
public final class SessionCookies {

  public static final String NAME = "cp_session";

  private final boolean secure;
  private final int maxAgeSeconds;

  public SessionCookies(final boolean secure, final int maxAgeSeconds) {
    this.secure = secure;
    this.maxAgeSeconds = maxAgeSeconds;
  }

  /** Read the raw token from the request cookie, or null if absent. */
  public static String read(final Context ctx) {
    return ctx.cookie(NAME);
  }

  /** Set the session cookie to {@code token}. */
  public void set(final Context ctx, final String token) {
    ctx.cookie(cookie(token, maxAgeSeconds));
  }

  /** Clear the session cookie (max-age 0 → browser deletes it). */
  public void clear(final Context ctx) {
    ctx.cookie(cookie("", 0));
  }

  private Cookie cookie(final String value, final int maxAge) {
    final Cookie cookie = new Cookie(NAME, value);
    cookie.setPath("/");
    cookie.setMaxAge(maxAge);
    cookie.setSecure(secure);
    cookie.setHttpOnly(true);
    cookie.setSameSite(SameSite.LAX);
    return cookie;
  }
}
