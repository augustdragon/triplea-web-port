plugins {
    id("triplea-java-library")
    id("java-test-fixtures")
}

// Web-port guardrail: game-core is becoming a headless engine library. Desktop-UI and
// web-module imports are forbidden in main sources. Warn-only until the Option A prune
// completes (flip ENFORCE to true in Tier 4); see docs/web-port/CHARTER.md.
val checkForbiddenImports = tasks.register("checkForbiddenImports") {
    description = "Forbids javax.swing / java.awt.event / org.triplea.swing / org.triplea.web imports in game-core main sources."
    group = "verification"
    val enforce = false
    // History extends javax.swing.tree.DefaultTreeModel and is serialized into save games;
    // removing that parent is Option C (serialization replacement) work, not pruning work.
    val allowlist = setOf("games/strategy/engine/history/History.java")
    val srcRoot = layout.projectDirectory.dir("src/main/java").asFile
    inputs.dir(srcRoot)
    outputs.upToDateWhen { false }
    doLast {
        val forbidden = Regex("""^import\s+(static\s+)?(javax\.swing|java\.awt\.event|org\.triplea\.swing|org\.triplea\.web)""")
        val violations = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filterNot { it.relativeTo(srcRoot).invariantSeparatorsPath in allowlist }
            .filter { file -> file.useLines { lines -> lines.any { forbidden.containsMatchIn(it) } } }
            .map { it.relativeTo(srcRoot).invariantSeparatorsPath }
            .sorted()
            .toList()
        if (violations.isNotEmpty()) {
            val message = "game-core forbidden-import violations (${violations.size} files):\n" +
                violations.joinToString("\n") { "  $it" }
            if (enforce) throw GradleException(message) else logger.warn(message)
        }
    }
}
tasks.named("check") { dependsOn(checkForbiddenImports) }

dependencies {
    implementation(project(":domain-data"))
    implementation(project(":map-data"))
    implementation(project(":lobby-client-data"))
    implementation(project(":java-extras"))
    implementation(project(":xml-reader"))
    testImplementation(project(":test-common"))
    // Configures mockito to use the legacy "subclass mock maker"
    // see https://github.com/mockito/mockito/releases/tag/v5.0.0 for more information

    testFixturesImplementation(project(":java-extras"))
    testFixturesImplementation(libs.bundles.junit)
    testFixturesImplementation(libs.bundles.mockito)
    testFixturesImplementation(libs.jsr305) {
        because("This provides javax.annotations.Nullable directly, instead of relying on pulling it as a transitive dep of websockets")
    }
    testFixturesImplementation(libs.guava)
    testFixturesImplementation(libs.hamcrest)
    testFixturesImplementation(libs.jetbrains.annotations)

    testFixturesCompileOnly(libs.lombok)
    testFixturesAnnotationProcessor(libs.lombok)
}
