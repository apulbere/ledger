plugins {
    id("ledger.spring-app")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.swagger.request.validator)

    // CDC test only: journal's main code never talks to Kafka.
    testImplementation(project(":journal-events"))
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

// The hand-written REST contract. Tests validate traffic against it, so it must be a test input:
// otherwise editing only the document would leave the test task up to date.
tasks.test {
    inputs.file("src/main/openapi/openapi.yaml")
        .withPropertyName("openapi")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The CDC test runs the real connector config, Postgres init script and Connect image.
    inputs.dir("../../../docker")
        .withPropertyName("docker")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
