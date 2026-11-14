plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework:spring-jdbc")                      // JdbcTemplate in IntegrationTest
    api("org.awaitility:awaitility")
    api("tools.jackson.core:jackson-databind")
    api("org.springframework.boot:spring-boot-resttestclient")   // TestRestTemplate (Spring Boot 4 moved it here)
    api(libs.wiremock)
}