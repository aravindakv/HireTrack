plugins { id("hiretrack.service-conventions") }

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")   // WebTestClient
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(libs.wiremock)
    testImplementation(project(":platform:test-support")) {
        // only TestJwt is needed; keep the servlet stack out of this WebFlux app
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-webmvc")
    }
}
