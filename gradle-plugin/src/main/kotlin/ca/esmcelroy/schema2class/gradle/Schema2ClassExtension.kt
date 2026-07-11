package ca.esmcelroy.schema2class.gradle

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class Schema2ClassExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
) {
    /** Schemas to generate from — mixed .xsd and .json entries are fine. */
    val schemas: NamedDomainObjectContainer<SchemaSpec> =
        objects.domainObjectContainer(SchemaSpec::class.java) { name ->
            objects.newInstance(SchemaSpec::class.java, name)
        }

    /** Where generated sources go. Default: build/generated/schema2class/kotlin */
    abstract val outputDirectory: DirectoryProperty

    /** Directory checked by schema2classVerifyGenerated. Defaults to outputDirectory. */
    abstract val verifyDirectory: DirectoryProperty

    init {
        outputDirectory.convention(layout.buildDirectory.dir("generated/schema2class/kotlin"))
        verifyDirectory.convention(outputDirectory)
    }

    fun schemas(action: Action<NamedDomainObjectContainer<SchemaSpec>>) {
        action.execute(schemas)
    }
}
