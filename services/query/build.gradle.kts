plugins {
    base
}

allprojects {
    group = "ledger"
}

tasks.build {
    dependsOn(":query-app:build")
}
