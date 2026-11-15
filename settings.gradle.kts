pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "boox-ink-bridge"

// The Onyx pen pipeline wrapper lives in its own repo, checked out beside this one.
include(":inkcontroller")
project(":inkcontroller").projectDir = file("../inksdk/inkcontroller")

include(":spike")
include(":app")
