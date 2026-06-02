plugins {
    id("triplea-java-library")
    id("application")
}

description =
    "Web-port control plane: OAuth + sessions, the lobby, the Postgres source-of-truth, " +
    "and orchestration of per-game JVM containers. Runs separately from the game containers."

application {
    mainClass.set("org.triplea.web.controlplane.ControlPlaneMain")
}

// The root build injects dropwizard-websockets (an old Jetty 9 stack) into every subproject for the
// game servers' WebSocket use. The control plane embeds Jetty via Javalin instead, so drop the
// legacy stack here to avoid two Jetty versions colliding on the classpath. This module's JVM does
// not use dropwizard-websockets at all.
configurations.all {
    exclude(group = "com.liveperson", module = "dropwizard-websockets")
}

dependencies {
    // HTTP API + (later) lobby WebSocket on embedded Jetty.
    implementation(libs.javalin)
    // Stateless session: sign/verify the JWT carried in the auth cookie (HMAC).
    implementation(libs.java.jwt)
    // Engine (parse-only): enumerate a map's playable powers for lobby tables. The control plane
    // never RUNS a game — that's the per-game container — so parsing here does not touch the
    // engine's process-global game state. MemoryPreferences isolates engine settings from disk.
    implementation(project(":game-core"))
    implementation(libs.sonatype.goodies.prefs)
    // Postgres source-of-truth: pooled JDBC + thin SQL mapping + schema migrations on boot.
    implementation(libs.hikaricp)
    implementation(libs.jdbi3.core)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
}

// Run the control plane against a local Postgres (see .docker/web-port-db.yml):
//   docker compose -f .docker/web-port-db.yml up -d
//   export CONTROL_PLANE_DB_PASSWORD=triplea_web
//   ./gradlew :game-control-plane:run
//
// We intentionally do NOT use the shadow (fat-jar) plugin here, unlike :game-headless. Flyway and
// other libraries discover plugins via ServiceLoader (META-INF/services), and a fat jar merges
// those files lossily under the current shadow version — Flyway then silently loses its
// SQL-migration resolver. The `application` plugin's `run` and `installDist` keep each dependency
// as its own jar, so ServiceLoader works correctly; that distribution is also what we containerize
// in P4.5.
tasks.named<JavaExec>("run") {
    group = "web-port"
    description = "Runs the web-port control plane (reads config from env vars)."
}
