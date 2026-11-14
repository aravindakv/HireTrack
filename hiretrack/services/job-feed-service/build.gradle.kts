plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:common-events"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
}
