plugins {
    id("triplea-java-library")
}

description = "Web port backend: map-asset conversion and (later) the headless engine host for the React client."

dependencies {
    // game-core gives us the engine's own geometry parser (PointFileReaderWriter) and, later,
    // the headless game-boot path. gson, guava, lombok and the JUnit5 test stack are injected
    // into every subproject by the root build.gradle.kts.
    implementation(project(":game-core"))

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
