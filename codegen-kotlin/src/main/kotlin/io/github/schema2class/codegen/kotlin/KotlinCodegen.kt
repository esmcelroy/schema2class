package io.github.schema2class.codegen.kotlin

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef

class KotlinCodegen {

    /**
     * Generates Kotlin source for every type in the model.
     * Returns a map of relative file path → file contents.
     * E.g. "io/github/example/Address.kt" → "package io.github.example\n\ndata class Address(...)"
     */
    fun generate(model: SchemaModel): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (type in model.types) {
            val source = generateSource(type, model.packageName)
            val relativePath = model.packageName.replace('.', '/') + "/" + type.kotlinName + ".kt"
            result[relativePath] = source
        }
        return result
    }

    private fun generateSource(type: TypeDefinition, packageName: String): String {
        val fileBuilder = FileSpec.builder(packageName, type.kotlinName)
        when (type) {
            is TypeDefinition.ComplexType -> fileBuilder.addType(generateComplexType(type, packageName))
            is TypeDefinition.EnumType -> {
                val (typeSpec, commentMap) = generateEnumType(type, packageName)
                fileBuilder.addType(typeSpec)
                val raw = fileBuilder.build().toString()
                return applyEnumComments(raw, commentMap)
            }
            is TypeDefinition.UnionType -> fileBuilder.addType(generateUnionType(type, packageName))
            is TypeDefinition.AliasType -> fileBuilder.addTypeAlias(generateAliasType(type, packageName))
        }
        return fileBuilder.build().toString()
    }

    private fun generateComplexType(type: TypeDefinition.ComplexType, packageName: String): TypeSpec {
        val allProps = listOfNotNull(type.contentProperty) + type.properties

        val constructorBuilder = FunSpec.constructorBuilder()
        val typeBuilder = TypeSpec.classBuilder(type.kotlinName)
        // A data class requires at least one constructor property; schemas can produce
        // empty types (e.g. xs:any-only wrappers), which must be plain classes.
        if (allProps.isNotEmpty()) {
            typeBuilder.addModifiers(KModifier.DATA)
        }

        type.documentation?.let { typeBuilder.addKdoc("%L", it) }

        val superType = type.superType
        if (superType != null) {
            typeBuilder.superclass(superType.toKotlinTypeName(packageName))
        }

        for (prop in allProps) {
            val typeName = prop.type.toKotlinTypeName(packageName)
            val resolvedTypeName = if (prop.nullable) typeName.copy(nullable = true) else typeName

            val paramBuilder = ParameterSpec.builder(prop.kotlinName, resolvedTypeName)
            val defaultValue = prop.defaultValue
            when {
                defaultValue != null -> paramBuilder.defaultValue(CodeBlock.of(defaultValue))
                prop.nullable -> paramBuilder.defaultValue("null")
            }

            constructorBuilder.addParameter(paramBuilder.build())

            val propBuilder = PropertySpec.builder(prop.kotlinName, resolvedTypeName)
                .initializer(prop.kotlinName)
            prop.documentation?.let { propBuilder.addKdoc("%L", it) }
            typeBuilder.addProperty(propBuilder.build())
        }

        if (allProps.isNotEmpty()) {
            typeBuilder.primaryConstructor(constructorBuilder.build())
        }
        return typeBuilder.build()
    }

    /**
     * Returns the generated TypeSpec plus a map of kotlinName -> serializedValue
     * for enum constants that need inline comments added in post-processing.
     */
    private fun generateEnumType(
        type: TypeDefinition.EnumType,
        @Suppress("UNUSED_PARAMETER") packageName: String,
    ): Pair<TypeSpec, Map<String, String>> {
        val typeBuilder = TypeSpec.enumBuilder(type.kotlinName)
        type.documentation?.let { typeBuilder.addKdoc("%L", it) }

        val commentMap = mutableMapOf<String, String>()

        for (value in type.values) {
            typeBuilder.addEnumConstant(value.kotlinName)
            if (value.serializedValue != value.kotlinName) {
                commentMap[value.kotlinName] = value.serializedValue
            }
        }

        return typeBuilder.build() to commentMap
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

    private fun generateUnionType(type: TypeDefinition.UnionType, packageName: String): TypeSpec {
        val sealedClassName = ClassName(packageName, type.kotlinName)
        val sealedBuilder = TypeSpec.classBuilder(type.kotlinName)
            .addModifiers(KModifier.SEALED)

        type.documentation?.let { sealedBuilder.addKdoc("%L", it) }

        for (variant in type.variants) {
            val variantTypeName = variant.type.toKotlinTypeName(packageName)
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
                .build()

            sealedBuilder.addType(variantClass)
        }

        return sealedBuilder.build()
    }

    private fun generateAliasType(type: TypeDefinition.AliasType, packageName: String): TypeAliasSpec {
        val aliasedTypeName = type.aliasedType.toKotlinTypeName(packageName)
        val builder = TypeAliasSpec.builder(type.kotlinName, aliasedTypeName)
        type.documentation?.let { builder.addKdoc("%L", it) }
        return builder.build()
    }

    private fun TypeRef.toKotlinTypeName(currentPackage: String): TypeName = when (this) {
        is TypeRef.Primitive -> primitiveToTypeName(type)
        is TypeRef.Named -> {
            val pkg = packageName ?: currentPackage
            ClassName(pkg, name)
        }
        is TypeRef.ListOf -> LIST.parameterizedBy(element.toKotlinTypeName(currentPackage))
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
}
