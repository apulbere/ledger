import ledger.avro.GenerateAvroJava

plugins {
    id("ledger.java")
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(libs.findLibrary("avro").get())
}

val generateAvroJava = tasks.register<GenerateAvroJava>("generateAvroJava") {
    source.from(fileTree("src/main/avro") { include("**/*.avsc") })
    outputDir = layout.buildDirectory.dir("generated/sources/avro")
}

sourceSets.main {
    java.srcDir(generateAvroJava)
}
