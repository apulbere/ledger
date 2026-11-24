plugins {
    id("ledger.spring-app")
}

dependencies {
    implementation("ledger:journal-events")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation(libs.swagger.request.validator)
}

// The hand-written REST contract. Tests validate traffic against it, so it must be a test input:
// otherwise editing only the document would leave the test task up to date.
tasks.test {
    inputs.file("src/main/openapi/openapi.yaml")
        .withPropertyName("openapi")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
