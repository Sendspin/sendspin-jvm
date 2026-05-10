pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.0"
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sendspin-jvm"

include(":sendspin-protocol")
include(":conformance-client")
