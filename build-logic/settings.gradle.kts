// NEW: build-logic included build — convention plugins carrying the module law (P1-GRADLE).
// Plugins resolve from the same repositories, in the same order, as the root build, so the one verification-metadata.xml pins
// what both builds download.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
