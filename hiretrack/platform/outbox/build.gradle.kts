plugins { id("hiretrack.library-conventions") }

dependencies {
    api(project(":platform:common-events"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")   // MeterRegistry bean (services get it via actuator)
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}