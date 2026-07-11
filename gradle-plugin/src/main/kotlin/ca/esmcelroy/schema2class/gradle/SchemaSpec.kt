package ca.esmcelroy.schema2class.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * One schema to generate from. Mixed formats are first-class: a single
 * `schema2class { schemas { ... } }` block can hold both `.xsd` and `.json`
 * entries (format is detected from the file extension).
 */
abstract class SchemaSpec @javax.inject.Inject constructor(
    private val name: String,
) : org.gradle.api.Named {

    @Internal
    override fun getName(): String = name

    /** The schema document (.xsd, or .json / .schema.json for JSON Schema). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    /** Optional external naming binding file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val nameBindings: RegularFileProperty

    /**
     * Kotlin package for the generated classes.
     * Required for JSON Schema. Optional for XSD: when absent, packages are
     * derived from targetNamespace(s) and xs:import/xs:include are resolved
     * transitively; when present, the XSD is parsed as a single document into
     * exactly this package.
     */
    @get:Input
    @get:Optional
    abstract val packageName: Property<String>

    /**
     * XSD only: explicit namespace-URI → package overrides used during
     * multi-file resolution (see docs/namespace-mapping.md).
     */
    @get:Input
    abstract val packageOverrides: MapProperty<String, String>

    /** One of NONE, KOTLINX_SERIALIZATION, XMLUTIL, JACKSON (case-insensitive). */
    @get:Input
    abstract val annotationMode: Property<String>

    /** Generate @JvmInline value classes for constrained simple types. */
    @get:Input
    abstract val valueClasses: Property<Boolean>

    /** Emit serializer metadata that omits null optional values when supported. */
    @get:Input
    abstract val omitNulls: Property<Boolean>

    init {
        annotationMode.convention("NONE")
        valueClasses.convention(false)
        omitNulls.convention(false)
        packageOverrides.convention(emptyMap())
    }
}
