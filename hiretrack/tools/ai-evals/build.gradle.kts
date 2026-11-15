plugins {
    id("hiretrack.java-conventions")
    application
}
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.9"))
    implementation("tools.jackson.core:jackson-databind")
}
application { mainClass = "com.aravindakv.hiretrack.evals.RunEvals" }