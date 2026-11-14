pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "hiretrack"

// Phase 1 adds: include("platform:common-web", ...)
