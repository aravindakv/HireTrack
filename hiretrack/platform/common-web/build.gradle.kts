plugins { id("hiretrack.library-conventions") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-restclient")      // RestClient.Builder for InternalRestClients
    api("org.springframework:spring-tx")                                // OptimisticLockingFailureException in GlobalExceptionHandler
    api("io.micrometer:micrometer-registry-prometheus")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")   // traces over OTLP
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(project(":platform:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")   // MockMvc
}