plugins {
    id("triplea-java-library")
    id("application")
}

description = "Web port backend: map-asset conversion and the headless engine host for the React client."

// The runnable distribution (installDist) packaged into the game-container Docker image. The
// container's entrypoint is the multiplayer host; the other mains stay as the JavaExec tasks below.
application {
    mainClass.set("org.triplea.web.server.game.WebPlayableServer")
    // Select this module's logback config (resolved as a classpath resource from its jar). The
    // engine ships logback.xml with root=WARN, which also hid this host's lifecycle INFO — including
    // the turn-deadline "AI takeover" line, which once made a wrongly-surrendered seat invisible in
    // the journal. game-web-server-logback.xml keeps the engine at WARN but logs our host at INFO.
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=game-web-server-logback.xml")
}

dependencies {
    // game-core gives us the engine's own geometry parser (PointFileReaderWriter) and, later,
    // the headless game-boot path. gson, guava, lombok and the JUnit5 test stack are injected
    // into every subproject by the root build.gradle.kts.
    implementation(project(":game-core"))
    // IntegerMap / engine collection types used directly by WebPlayer (game-core depends on this
    // but doesn't re-export it).
    implementation(project(":java-extras"))
    // In-memory preferences so the headless server doesn't touch the user's real TripleA settings.
    implementation(libs.sonatype.goodies.prefs)

    testImplementation(project(":test-common"))
    // TestMapGameData / TestMapGameDataLoader for loading real test maps in tests.
    testImplementation(testFixtures(project(":game-core")))
}

// Exports a map folder's geometry to geometry.json for the web client.
// Run: ./gradlew :game-web-server:exportGeometry --args="<mapFolder> <outputJsonFile>"
tasks.register<JavaExec>("exportGeometry") {
    group = "web-port"
    description = "Converts a map folder's polygons.txt/centers.txt into geometry.json."
    mainClass.set("org.triplea.web.server.map.GeometryExportCli")
    classpath = sourceSets["main"].runtimeClasspath
}

// Smoke-runs an AI game in-process to verify the engine-host path.
// Run: ./gradlew :game-web-server:runAiGame --args="<gameXml> [maxRounds]"
tasks.register<JavaExec>("runAiGame") {
    group = "web-port"
    description = "Runs an AI game in-process and prints step progression."
    mainClass.set("org.triplea.web.server.game.AiGameRunnerCli")
    classpath = sourceSets["main"].runtimeClasspath
}

// Live spectator: runs an AI game and pushes state over WebSocket for the web client.
// Run: ./gradlew :game-web-server:runSpectator --args="<gameXml> [port] [maxRounds] [stepDelayMs]"
tasks.register<JavaExec>("runSpectator") {
    group = "web-port"
    description = "Runs an AI game and broadcasts state snapshots over WebSocket."
    mainClass.set("org.triplea.web.server.game.WebSpectatorServer")
    classpath = sourceSets["main"].runtimeClasspath
}

// Playable multiplayer: browsers claim human seats / assign AI in a setup phase, then play.
// Run: ./gradlew :game-web-server:runPlayable \
//   --args="<gameXml> [--port=8080] [--max-rounds=20] [--step-delay-ms=300] [--game-id=<id>] [--save-ref=<slot>]"
// Seats are chosen in the browser. The control plane spawns this with --game-id (per-game save slot)
// and, to resume, --save-ref.
tasks.register<JavaExec>("runPlayable") {
    group = "web-port"
    description = "Hosts a multiplayer game: browsers claim seats over WebSocket, then play."
    mainClass.set("org.triplea.web.server.game.WebPlayableServer")
    classpath = sourceSets["main"].runtimeClasspath
}
