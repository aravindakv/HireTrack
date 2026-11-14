plugins { id("hiretrack.library-conventions") }

dependencies {
    api(project(":platform:common-web"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}