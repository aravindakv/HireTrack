pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "hiretrack"

include(
    "platform:common-web",
    "platform:common-events",
    "platform:outbox",
    "platform:idempotency",
    "platform:test-support",
    "services:api-gateway",
    "services:identity-service",
    "services:resume-service",
    "services:application-service",
    "services:ai-service",
    "services:match-service",
    "services:analytics-service",
    "services:job-feed-service",
)
