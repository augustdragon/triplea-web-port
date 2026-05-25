plugins {
    id("triplea-java-library")
}

description = "Web port backend: map-asset conversion and (later) the headless engine host for the React client."

dependencies {
    // game-core gives us the engine's own geometry parser (PointFileReaderWriter) and, later,
    // the headless game-boot path. gson, guava, lombok and the JUnit5 test stack are injected
    // into every subproject by the root build.gradle.kts.
    implementation(project(":game-core"))
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
