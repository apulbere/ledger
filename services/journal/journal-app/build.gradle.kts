plugins {
    id("ledger.spring-app")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.springdoc.webmvc.ui)

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.swagger.request.validator)
}

// Serve the hand-written contract at /openapi.yaml; Swagger UI renders it (springdoc in application.yaml).
tasks.processResources {
    from("src/main/openapi") {
        into("static")
    }
}

// The hand-written REST contract. Tests validate traffic against it, so it must be a test input:
// otherwise editing only the document would leave the test task up to date.
tasks.test {
    inputs.file("src/main/openapi/openapi.yaml")
        .withPropertyName("openapi")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
