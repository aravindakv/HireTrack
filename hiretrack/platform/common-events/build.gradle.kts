plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter-jackson")   // Jackson 3 + the ObjectMapper bean (Boot 4 moved its auto-config here)
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}