plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
    testImplementation(libs.pact.consumer)   // writes contracts/pacts/match-service-ai-service.json
}

tasks.withType<Test>().configureEach {
    systemProperty("pact.rootDir", rootProject.file("contracts/pacts").path)
    systemProperty("pact.verifier.publishResults", "false")
}
