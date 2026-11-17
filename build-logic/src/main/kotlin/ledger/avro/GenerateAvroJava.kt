package ledger.avro

import org.apache.avro.Schema
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.generic.GenericData
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/** Generates Java classes from `.avsc` schemas with the Avro SpecificCompiler. */
@CacheableTask
abstract class GenerateAvroJava : DefaultTask() {

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        // One parser for all files so schemas can reference named types defined in earlier files.
        val parser = Schema.Parser()
        source.files.sortedBy { it.path }.forEach { file ->
            SpecificCompiler(parser.parse(file)).apply {
                setStringType(GenericData.StringType.String)
            }.compileToDestination(file, out)
        }
    }
}
