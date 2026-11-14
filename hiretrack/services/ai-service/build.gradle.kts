plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.openai.java)
    implementation(libs.anthropic.java)
    implementation(libs.resilience4j.bulkhead)       // core libraries: no Spring Boot version coupling
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
