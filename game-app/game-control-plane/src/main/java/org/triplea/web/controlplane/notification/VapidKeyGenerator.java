package org.triplea.web.controlplane.notification;

/**
 * One-off setup helper: prints a fresh VAPID key pair for the {@code CONTROL_PLANE_VAPID_*} env
 * vars. Run via {@code ./gradlew :game-control-plane:generateVapidKeys}. Generate once per
 * deployment and keep the private key secret (env only — never commit it).
 */
public final class VapidKeyGenerator {
  private VapidKeyGenerator() {}

  public static void main(final String[] args) {
    final String[] keys = VapidKeys.generate();
    System.out.println("# Web Push VAPID keys — add to the control-plane environment:");
    System.out.println("CONTROL_PLANE_VAPID_PUBLIC_KEY=" + keys[0]);
    System.out.println("CONTROL_PLANE_VAPID_PRIVATE_KEY=" + keys[1]);
    System.out.println("# CONTROL_PLANE_VAPID_SUBJECT=mailto:you@example.com");
  }
}
