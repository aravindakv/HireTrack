plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation(project(":platform:common-web"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:idempotency"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.pdfbox)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.s3)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":platform:test-support"))
    testImplementation(libs.s3mock.testcontainers)
}
