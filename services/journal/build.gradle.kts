plugins {
    base
}

allprojects {
    group = "ledger"
}

tasks.build {
    dependsOn(":journal-app:build", ":journal-events:build")
}
