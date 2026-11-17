// Aggregates the included builds so `./gradlew build` at the root builds everything.
listOf("build", "clean").forEach { name ->
    tasks.register(name) {
        group = "build"
        dependsOn(gradle.includedBuilds.filter { it.name != "build-logic" }.map { it.task(":$name") })
    }
}
