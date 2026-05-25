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
}
