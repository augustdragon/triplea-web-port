@file:Suppress("UnstableApiUsage") // For repository declarations in settings

import org.gradle.api.initialization.resolve.RepositoriesMode
import java.net.URI

pluginManagement {
    includeBuild("gradle/build-logic")
}

plugins {
    id("org.triplea.failure-summary-plugin")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/triplea-game/triplea")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "triplea"

include(":domain-data")
project(":domain-data").projectDir = file("game-app/domain-data")
include(":game-core")
project(":game-core").projectDir = file("game-app/game-core")
include(":game-web-server")
project(":game-web-server").projectDir = file("game-app/game-web-server")
include(":game-control-plane")
project(":game-control-plane").projectDir = file("game-app/game-control-plane")
include(":map-data")
project(":map-data").projectDir = file("game-app/map-data")
include(":smoke-testing")
project(":smoke-testing").projectDir = file("game-app/smoke-testing")

include(":lobby-client-data")
project(":lobby-client-data").projectDir = file("http-clients/lobby-client-data")


include(":java-extras")
project(":java-extras").projectDir = file("lib/java-extras")
include(":test-common")
project(":test-common").projectDir = file("lib/test-common")
include(":xml-reader")
project(":xml-reader").projectDir = file("lib/xml-reader")
