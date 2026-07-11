package ca.esmcelroy.schema2class.codegen.kotlin

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import ca.esmcelroy.schema2class.core.ir.EnumValue
import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.PropertyDefinition
import ca.esmcelroy.schema2class.core.ir.PropertyKind
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.ir.SourceFormat
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef

/** Which serialization annotations the generated source carries. */
enum class AnnotationMode {
    /** Plain data classes, no annotations. */
    NONE,

    /**
     * kotlinx.serialization: @Serializable on classes/enums/sealed hierarchies,
     * @SerialName where the wire name differs from the Kotlin name, and
     * generated string serializers on java.math/java.time property types
     * (BigDecimal, LocalDate, OffsetDateTime, Duration), which have no built-in
     * kotlinx serializers.
     */
    KOTLINX_SERIALIZATION,

    /**
     * Everything [KOTLINX_SERIALIZATION] emits, plus pdvrieze/xmlutil annotations
     * so XSD-sourced models round-trip XML: @XmlSerialName (type name + target
     * namespace) on types, @XmlElement(true/false) for element vs attribute
     * properties, and @XmlValue on the xs:simpleContent content property.
     */
    XMLUTIL,

    /**
     * Jackson annotations. JSON Schema models use @JsonProperty for wire-name
     * mappings. XSD models additionally use jackson-dataformat-xml annotations
     * for attributes, element names, list wrappers, and text content.
     */
    JACKSON,
}

class KotlinCodegen(private val options: Options = Options()) {

    data class Options(
        val annotationMode: AnnotationMode = AnnotationMode.NONE,
        val generateValueClasses: Boolean = false,
        val omitNulls: Boolean = false,
    )

    private val kotlinxMode: Boolean
        get() = options.annotationMode == AnnotationMode.KOTLINX_SERIALIZATION ||
            options.annotationMode == AnnotationMode.XMLUTIL

    private val xmlMode: Boolean
        get() = options.annotationMode == AnnotationMode.XMLUTIL

    private val jacksonMode: Boolean
        get() = options.annotationMode == AnnotationMode.JACKSON

    /**
     * Generates Kotlin source for every type in the model.
     * Returns a map of relative file path → file contents.
     * E.g. "ca/example/Address.kt" → "package ca.example\n\ndata class Address(...)"
     */
    fun generate(model: SchemaModel): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (type in model.types) {
            val source = generateSource(type, model.packageName, model.wireNamespace, model.sourceFormat)
            val relativePath = model.packageName.replace('.', '/') + "/" + type.kotlinName + ".kt"
            result[relativePath] = source
        }
        return result
    }

    private fun generateSource(
        type: TypeDefinition,
        packageName: String,
        namespace: String?,
        sourceFormat: SourceFormat,
    ): String {
        val fileBuilder = FileSpec.builder(packageName, type.kotlinName)
        when (type) {
            is TypeDefinition.ComplexType -> fileBuilder.addType(
                generateComplexType(type, packageName, namespace, sourceFormat),
            )
            is TypeDefinition.EnumType -> {
                val (typeSpec, commentMap) = generateEnumType(type, namespace)
                fileBuilder.addType(typeSpec)
                val raw = fileBuilder.build().toString()
                return applyEnumComments(raw, commentMap)
            }
            is TypeDefinition.UnionType -> fileBuilder.addType(generateUnionType(type, packageName, namespace))
            is TypeDefinition.AliasType -> {
                if (options.generateValueClasses && type.constraints.isNotEmpty()) {
                    fileBuilder.addType(generateValueClass(type, packageName, namespace))
                } else {
                    fileBuilder.addTypeAlias(generateAliasType(type, packageName))
                }
            }
        }
        return fileBuilder.build().toString()
    }

    private fun generateComplexType(
        type: TypeDefinition.ComplexType,
        packageName: String,
        namespace: String?,
        sourceFormat: SourceFormat,
    ): TypeSpec {
        val allProps = listOfNotNull(type.contentProperty) + type.properties
        val ownerClassName = ClassName(packageName, type.kotlinName)

        val constructorBuilder = FunSpec.constructorBuilder()
        val typeBuilder = TypeSpec.classBuilder(type.kotlinName)
        // A data class requires at least one constructor property; schemas can produce
        // empty types (e.g. xs:any-only wrappers), which must be plain classes.
        if (allProps.isNotEmpty()) {
            typeBuilder.addModifiers(KModifier.DATA)
        }
        addTypeAnnotations(typeBuilder, type.schemaName, type.kotlinName, namespace)

        type.documentation?.let { typeBuilder.addKdoc("%L", it.toKdocText()) }

        // superType is provenance only: Kotlin data classes are final, so schema
        // inheritance is flattened into properties by InheritanceFlattener before
        // codegen. Emitting `: Parent()` here would not compile.

        for (prop in allProps) {
            val typeName = propertyTypeName(prop, packageName, ownerClassName)
            val resolvedTypeName = if (prop.nullable) typeName.copy(nullable = true) else typeName

            val paramBuilder = ParameterSpec.builder(prop.kotlinName, resolvedTypeName)
            val defaultValue = prop.defaultValue ?: prop.fixedValue
            when {
                defaultValue != null -> paramBuilder.defaultValue(CodeBlock.of(defaultValue))
                prop.nullable -> paramBuilder.defaultValue("null")
            }

            constructorBuilder.addParameter(paramBuilder.build())

            val propBuilder = PropertySpec.builder(prop.kotlinName, resolvedTypeName)
                .initializer(prop.kotlinName)
            if (kotlinxMode && prop.schemaName != prop.kotlinName) {
                propBuilder.addAnnotation(serialNameAnnotation(prop.schemaName))
            }
            if (jacksonMode) {
                addJacksonPropertyAnnotations(propBuilder, prop, sourceFormat)
            }
            if (xmlMode) {
                propBuilder.addAnnotation(kindAnnotation(prop.kind))
            }
            prop.documentation?.let { propBuilder.addKdoc("%L", it.toKdocText()) }
            typeBuilder.addProperty(propBuilder.build())
        }

        addFixedValueChecks(typeBuilder, allProps)

        if (kotlinxMode) {
            allProps.flatMap { it.type.contextualPrimitiveTypes() }
                .distinct()
                .forEach { typeBuilder.addType(stringSerializerType(it)) }
        }

        if (allProps.isNotEmpty()) {
            typeBuilder.primaryConstructor(constructorBuilder.build())
        }
        return typeBuilder.build()
    }

    private fun addFixedValueChecks(typeBuilder: TypeSpec.Builder, properties: List<PropertyDefinition>) {
        val fixedProps = properties.filter { it.fixedValue != null }
        if (fixedProps.isEmpty()) return

        val block = CodeBlock.builder()
        fixedProps.forEach { prop ->
            block.addStatement(
                "require(%N == %L) { %S }",
                prop.kotlinName,
                prop.fixedValue,
                "schema2class: property '${prop.schemaName}' is fixed to ${prop.fixedValue}",
            )
        }
        typeBuilder.addInitializerBlock(block.build())
    }

    private fun String.toKdocText(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("*/", "* /")

    /**
     * Returns the generated TypeSpec plus a map of kotlinName -> serializedValue
     * for enum constants that need inline comments added in post-processing.
     */
    private fun generateEnumType(
        type: TypeDefinition.EnumType,
        namespace: String?,
    ): Pair<TypeSpec, Map<String, String>> {
        val typeBuilder = TypeSpec.enumBuilder(type.kotlinName)
        addTypeAnnotations(typeBuilder, type.schemaName, type.kotlinName, namespace)
        type.documentation?.let { typeBuilder.addKdoc("%L", it.toKdocText()) }

        val commentMap = mutableMapOf<String, String>()

        for (value in type.values) {
            if ((kotlinxMode || jacksonMode) && value.serializedValue != value.kotlinName) {
                val annotation = if (jacksonMode) {
                    jsonPropertyAnnotation(value.serializedValue)
                } else {
                    serialNameAnnotation(value.serializedValue)
                }
                typeBuilder.addEnumConstant(value.kotlinName, enumConstantType(value, annotation))
            } else if (value.documentation != null) {
                typeBuilder.addEnumConstant(value.kotlinName, enumConstantType(value, null))
            } else {
                typeBuilder.addEnumConstant(value.kotlinName)
            }
            if (value.serializedValue != value.kotlinName) {
                commentMap[value.kotlinName] = value.serializedValue
            }
        }

        return typeBuilder.build() to commentMap
    }

    private fun enumConstantType(value: EnumValue, annotation: AnnotationSpec?): TypeSpec {
        val builder = TypeSpec.anonymousClassBuilder()
        annotation?.let { builder.addAnnotation(it) }
        value.documentation?.let { builder.addKdoc("%L", it.toKdocText()) }
        return builder.build()
    }

    /**
     * Post-processes the raw file output to append `// "serializedValue"` inline comments
     * after enum constant declarations.
     */
    private fun applyEnumComments(source: String, commentMap: Map<String, String>): String {
        if (commentMap.isEmpty()) return source
        val lines = source.lines().toMutableList()
        for (i in lines.indices) {
            val trimmed = lines[i].trimEnd().trimEnd(',').trim()
            val serialized = commentMap[trimmed]
            if (serialized != null) {
                // Keep the original line content but append a comment
                val line = lines[i].trimEnd()
                // Ensure comma is present (KotlinPoet may or may not include it)
                val lineWithComma = if (line.trimEnd().endsWith(",")) line else "$line,"
                lines[i] = "$lineWithComma // \"$serialized\""
            }
        }
        return lines.joinToString("\n")
    }

    private fun generateUnionType(
        type: TypeDefinition.UnionType,
        packageName: String,
        namespace: String?,
    ): TypeSpec {
        val sealedClassName = ClassName(packageName, type.kotlinName)
        val sealedBuilder = TypeSpec.classBuilder(type.kotlinName)
            .addModifiers(KModifier.SEALED)
        addTypeAnnotations(sealedBuilder, type.schemaName, type.kotlinName, namespace)
        val discriminator = type.discriminatorProperty
        if (kotlinxMode && discriminator != null) {
            // @JsonClassDiscriminator is experimental in kotlinx.serialization.json
            sealedBuilder.addAnnotation(
                AnnotationSpec.builder(OPT_IN)
                    .addMember("%T::class", EXPERIMENTAL_SERIALIZATION_API)
                    .build(),
            )
            sealedBuilder.addAnnotation(
                AnnotationSpec.builder(JSON_CLASS_DISCRIMINATOR)
                    .addMember("%S", discriminator)
                    .build(),
            )
        }

        type.documentation?.let { sealedBuilder.addKdoc("%L", it.toKdocText()) }

        for (variant in type.variants) {
            val variantTypeName = variant.type.toKotlinTypeName(packageName).withContextualIfNeeded(variant.type)
            val paramSpec = ParameterSpec.builder("value", variantTypeName).build()
            val propSpec = PropertySpec.builder("value", variantTypeName)
                .initializer("value")
                .build()
            val variantConstructor = FunSpec.constructorBuilder()
                .addParameter(paramSpec)
                .build()

            val variantClass = TypeSpec.classBuilder(variant.kotlinName)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(variantConstructor)
                .addProperty(propSpec)
                .superclass(sealedClassName)
                .apply { if (kotlinxMode) addAnnotation(SERIALIZABLE) }
                .build()

            sealedBuilder.addType(variantClass)
        }

        return sealedBuilder.build()
    }

    private fun generateAliasType(type: TypeDefinition.AliasType, packageName: String): TypeAliasSpec {
        val aliasedTypeName = type.aliasedType.toKotlinTypeName(packageName)
        val builder = TypeAliasSpec.builder(type.kotlinName, aliasedTypeName)
        type.documentation?.let { builder.addKdoc("%L", it.toKdocText()) }
        return builder.build()
    }

    private fun generateValueClass(
        type: TypeDefinition.AliasType,
        packageName: String,
        namespace: String?,
    ): TypeSpec {
        val ownerClassName = ClassName(packageName, type.kotlinName)
        val typeName = type.aliasedType.toKotlinTypeName(packageName)
            .withContextualIfNeeded(type.aliasedType, ownerClassName)
        val constructor = FunSpec.constructorBuilder()
            .addParameter("value", typeName)
            .build()
        val builder = TypeSpec.classBuilder(type.kotlinName)
            .addModifiers(KModifier.VALUE)
            .addAnnotation(JVM_INLINE)
            .primaryConstructor(constructor)
            .addProperty(
                PropertySpec.builder("value", typeName)
                    .initializer("value")
                    .build(),
            )
        addTypeAnnotations(builder, type.schemaName, type.kotlinName, namespace)
        type.documentation?.let { builder.addKdoc("%L", it.toKdocText()) }
        if (kotlinxMode) {
            type.aliasedType.contextualPrimitiveTypes()
                .forEach { builder.addType(stringSerializerType(it)) }
        }
        return builder.build()
    }

    // ── Annotation helpers ────────────────────────────────────────────────────

    private fun addTypeAnnotations(
        builder: TypeSpec.Builder,
        schemaName: String,
        kotlinName: String,
        namespace: String?,
    ) {
        if (kotlinxMode) {
            builder.addAnnotation(SERIALIZABLE)
        }
        if (kotlinxMode && schemaName != kotlinName) {
            builder.addAnnotation(serialNameAnnotation(schemaName))
        }
        if (xmlMode) {
            val xmlName = AnnotationSpec.builder(XML_SERIAL_NAME)
                .addMember("value = %S", schemaName)
            if (namespace != null) {
                xmlName.addMember("namespace = %S", namespace)
            }
            builder.addAnnotation(xmlName.build())
        }
        if (jacksonMode && options.omitNulls) {
            builder.addAnnotation(jsonIncludeNonNullAnnotation())
        }
    }

    private fun serialNameAnnotation(wireName: String): AnnotationSpec =
        AnnotationSpec.builder(SERIAL_NAME).addMember("%S", wireName).build()

    private fun jsonPropertyAnnotation(wireName: String): AnnotationSpec =
        AnnotationSpec.builder(JSON_PROPERTY).addMember("%S", wireName).build()

    private fun jsonIncludeNonNullAnnotation(): AnnotationSpec =
        AnnotationSpec.builder(JSON_INCLUDE)
            .addMember("%T.%L", JSON_INCLUDE.nestedClass("Include"), "NON_NULL")
            .build()

    private fun kindAnnotation(kind: PropertyKind): AnnotationSpec = when (kind) {
        PropertyKind.ELEMENT -> AnnotationSpec.builder(XML_ELEMENT).addMember("%L", true).build()
        PropertyKind.ATTRIBUTE -> AnnotationSpec.builder(XML_ELEMENT).addMember("%L", false).build()
        PropertyKind.CONTENT -> AnnotationSpec.builder(XML_VALUE).build()
    }

    private fun addJacksonPropertyAnnotations(
        builder: PropertySpec.Builder,
        prop: PropertyDefinition,
        sourceFormat: SourceFormat,
    ) {
        if (sourceFormat != SourceFormat.XSD) {
            if (prop.schemaName != prop.kotlinName) {
                builder.addAnnotation(jsonPropertyAnnotation(prop.schemaName))
            }
            return
        }

        when (prop.kind) {
            PropertyKind.CONTENT -> builder.addAnnotation(JACKSON_XML_TEXT)
            PropertyKind.ATTRIBUTE -> builder.addAnnotation(
                AnnotationSpec.builder(JACKSON_XML_PROPERTY)
                    .addMember("localName = %S", prop.schemaName)
                    .addMember("isAttribute = %L", true)
                    .build(),
            )
            PropertyKind.ELEMENT -> {
                builder.addAnnotation(
                    AnnotationSpec.builder(JACKSON_XML_PROPERTY)
                        .addMember("localName = %S", prop.schemaName)
                        .build(),
                )
                if (prop.type is TypeRef.ListOf) {
                    builder.addAnnotation(
                        AnnotationSpec.builder(JACKSON_XML_ELEMENT_WRAPPER)
                            .addMember("useWrapping = %L", false)
                            .build(),
                    )
                }
            }
        }
    }

    private fun propertyTypeName(
        prop: PropertyDefinition,
        packageName: String,
        ownerClassName: ClassName,
    ): TypeName =
        prop.type.toKotlinTypeName(packageName).withContextualIfNeeded(prop.type, ownerClassName)

    /**
     * Attaches concrete string serializers to java.math/java.time types (or the
     * element type of a list of them) in annotated modes. kotlinx.serialization
     * has no built-in serializers for those; concrete string descriptors also
     * keep XMLUTIL @XmlValue content as text.
     */
    private fun TypeName.withContextualIfNeeded(
        ref: TypeRef,
        ownerClassName: ClassName? = null,
    ): TypeName {
        if (!kotlinxMode) return this
        return when (ref) {
            is TypeRef.Primitive ->
                if (ref.type.needsContextual) {
                    val annotation = if (ownerClassName != null) {
                        stringSerializerAnnotation(ownerClassName, ref.type)
                    } else {
                        contextualAnnotation()
                    }
                    copy(annotations = annotations + annotation)
                } else {
                    this
                }
            is TypeRef.ListOf -> {
                if (this !is com.squareup.kotlinpoet.ParameterizedTypeName) return this
                val element = typeArguments.single().withContextualIfNeeded(ref.element, ownerClassName)
                rawType.parameterizedBy(element)
            }
            is TypeRef.MapOf -> {
                if (this !is com.squareup.kotlinpoet.ParameterizedTypeName) return this
                rawType.parameterizedBy(
                    typeArguments[0].withContextualIfNeeded(ref.key, ownerClassName),
                    typeArguments[1].withContextualIfNeeded(ref.value, ownerClassName),
                )
            }
            is TypeRef.Named -> this
        }
    }

    private val PrimitiveType.needsContextual: Boolean
        get() = when (this) {
            PrimitiveType.DECIMAL, PrimitiveType.DATE,
            PrimitiveType.DATE_TIME, PrimitiveType.DURATION,
            -> true
            else -> false
        }

    private fun contextualAnnotation(): AnnotationSpec = AnnotationSpec.builder(CONTEXTUAL).build()

    private fun stringSerializerAnnotation(ownerClassName: ClassName, type: PrimitiveType): AnnotationSpec =
        AnnotationSpec.builder(SERIALIZABLE)
            .addMember("with = %T::class", ownerClassName.nestedClass(serializerObjectName(type)))
            .build()

    private fun TypeRef.contextualPrimitiveTypes(): Set<PrimitiveType> = when (this) {
        is TypeRef.Primitive -> if (type.needsContextual) setOf(type) else emptySet()
        is TypeRef.ListOf -> element.contextualPrimitiveTypes()
        is TypeRef.MapOf -> key.contextualPrimitiveTypes() + value.contextualPrimitiveTypes()
        is TypeRef.Named -> emptySet()
    }

    private fun serializerObjectName(type: PrimitiveType): String = when (type) {
        PrimitiveType.DECIMAL -> "Schema2ClassBigDecimalAsStringSerializer"
        PrimitiveType.DATE -> "Schema2ClassLocalDateAsStringSerializer"
        PrimitiveType.DATE_TIME -> "Schema2ClassOffsetDateTimeAsStringSerializer"
        PrimitiveType.DURATION -> "Schema2ClassDurationAsStringSerializer"
        else -> error("No generated string serializer for $type")
    }

    private fun stringSerializerType(type: PrimitiveType): TypeSpec {
        val targetType = primitiveToTypeName(type)
        val serialName = "ca.esmcelroy.schema2class.${serializerObjectName(type)}"
        val descriptor = PropertySpec.builder("descriptor", SERIAL_DESCRIPTOR)
            .addModifiers(KModifier.OVERRIDE)
            .initializer(
                "%T(%S, %T)",
                PRIMITIVE_SERIAL_DESCRIPTOR,
                serialName,
                PRIMITIVE_KIND_STRING,
            )
            .build()
        val serializeValue = when (type) {
            PrimitiveType.DECIMAL -> "value.toPlainString()"
            PrimitiveType.DATE, PrimitiveType.DATE_TIME, PrimitiveType.DURATION -> "value.toString()"
            else -> error("No generated string serializer for $type")
        }
        val deserializeBody = when (type) {
            PrimitiveType.DECIMAL -> CodeBlock.of("return %T(decoder.decodeString())", targetType)
            PrimitiveType.DATE, PrimitiveType.DATE_TIME, PrimitiveType.DURATION ->
                CodeBlock.of("return %T.parse(decoder.decodeString())", targetType)
            else -> error("No generated string serializer for $type")
        }

        return TypeSpec.objectBuilder(serializerObjectName(type))
            .addSuperinterface(K_SERIALIZER.parameterizedBy(targetType))
            .addProperty(descriptor)
            .addFunction(
                FunSpec.builder("serialize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("encoder", ENCODER)
                    .addParameter("value", targetType)
                    .addStatement("encoder.encodeString(%L)", serializeValue)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("deserialize")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("decoder", DECODER)
                    .returns(targetType)
                    .addCode(deserializeBody)
                    .build(),
            )
            .build()
    }

    // ── Type name resolution ──────────────────────────────────────────────────

    private fun TypeRef.toKotlinTypeName(currentPackage: String): TypeName = when (this) {
        is TypeRef.Primitive -> primitiveToTypeName(type)
        is TypeRef.Named -> {
            val pkg = packageName ?: currentPackage
            ClassName(pkg, name)
        }
        is TypeRef.ListOf -> LIST.parameterizedBy(element.toKotlinTypeName(currentPackage))
        is TypeRef.MapOf -> MAP.parameterizedBy(
            key.toKotlinTypeName(currentPackage),
            value.toKotlinTypeName(currentPackage),
        )
    }

    private fun primitiveToTypeName(type: PrimitiveType): TypeName = when (type) {
        PrimitiveType.STRING -> String::class.asClassName()
        PrimitiveType.INT -> Int::class.asClassName()
        PrimitiveType.LONG -> Long::class.asClassName()
        PrimitiveType.FLOAT -> Float::class.asClassName()
        PrimitiveType.DOUBLE -> Double::class.asClassName()
        PrimitiveType.BOOLEAN -> Boolean::class.asClassName()
        PrimitiveType.DECIMAL -> ClassName("java.math", "BigDecimal")
        PrimitiveType.DATE -> ClassName("java.time", "LocalDate")
        PrimitiveType.DATE_TIME -> ClassName("java.time", "OffsetDateTime")
        PrimitiveType.DURATION -> ClassName("java.time", "Duration")
        PrimitiveType.BYTES -> ByteArray::class.asClassName()
        PrimitiveType.URI -> String::class.asClassName()
        PrimitiveType.ANY -> Any::class.asClassName()
    }

    private companion object {
        val SERIALIZABLE = ClassName("kotlinx.serialization", "Serializable")
        val SERIAL_NAME = ClassName("kotlinx.serialization", "SerialName")
        val CONTEXTUAL = ClassName("kotlinx.serialization", "Contextual")
        val JSON_PROPERTY = ClassName("com.fasterxml.jackson.annotation", "JsonProperty")
        val JSON_INCLUDE = ClassName("com.fasterxml.jackson.annotation", "JsonInclude")
        val JACKSON_XML_PROPERTY =
            ClassName("com.fasterxml.jackson.dataformat.xml.annotation", "JacksonXmlProperty")
        val JACKSON_XML_ELEMENT_WRAPPER =
            ClassName("com.fasterxml.jackson.dataformat.xml.annotation", "JacksonXmlElementWrapper")
        val JACKSON_XML_TEXT =
            ClassName("com.fasterxml.jackson.dataformat.xml.annotation", "JacksonXmlText")
        val JVM_INLINE = ClassName("kotlin.jvm", "JvmInline")
        val K_SERIALIZER = ClassName("kotlinx.serialization", "KSerializer")
        val SERIAL_DESCRIPTOR = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
        val PRIMITIVE_SERIAL_DESCRIPTOR =
            ClassName("kotlinx.serialization.descriptors", "PrimitiveSerialDescriptor")
        val PRIMITIVE_KIND_STRING =
            ClassName("kotlinx.serialization.descriptors", "PrimitiveKind", "STRING")
        val ENCODER = ClassName("kotlinx.serialization.encoding", "Encoder")
        val DECODER = ClassName("kotlinx.serialization.encoding", "Decoder")
        val XML_SERIAL_NAME = ClassName("nl.adaptivity.xmlutil.serialization", "XmlSerialName")
        val XML_ELEMENT = ClassName("nl.adaptivity.xmlutil.serialization", "XmlElement")
        val XML_VALUE = ClassName("nl.adaptivity.xmlutil.serialization", "XmlValue")
        val OPT_IN = ClassName("kotlin", "OptIn")
        val EXPERIMENTAL_SERIALIZATION_API =
            ClassName("kotlinx.serialization", "ExperimentalSerializationApi")
        val JSON_CLASS_DISCRIMINATOR =
            ClassName("kotlinx.serialization.json", "JsonClassDiscriminator")
    }
}
