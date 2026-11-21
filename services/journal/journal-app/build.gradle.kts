plugins {
    id("ledger.spring-app")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
