plugins {
    id("hiretrack.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.findVersion("spring-cloud").get()}")
    }
}

// Coverage gate from spec 10.7: >= 80% lines on domain and service packages.
tasks.register<JacocoCoverageVerification>("coverageGate") {
    dependsOn(tasks.named("test"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/test.exec"))
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/java/main")) {
            include("**/domain/**", "**/service/**")
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("coverageGate") }

springBoot {
    buildInfo()   // exposes version in /actuator/info and on metrics
}

// Only the executable boot jar is built, so Docker images (Phase 12) can copy one unambiguous file.
tasks.named<Jar>("jar") { enabled = false }