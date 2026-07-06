package io.github.schema2class.parser.jsonschema

import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class JsonSchemaParserTest {

    private val parser = JsonSchemaParser()

    private fun parse(json: String) =
        parser.parse(json.trimIndent().byteInputStream(), "com.example")

    // ── Test 1: Simple object — required vs optional → nullable flags ─────

    @Test
    fun `required properties are non-nullable, optional are nullable`() {
        val model = parse(
            """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age":  { "type": "integer" }
              },
              "required": ["name"]
            }
            """
        )

        val rootType = model.types.find { it is TypeDefinition.ComplexType }
            .shouldNotBeNull()
            .shouldBeInstanceOf<TypeDefinition.ComplexType>()

        val nameProp = rootType.properties.find { it.schemaName == "name" }.shouldNotBeNull()
        nameProp.nullable shouldBe false
        nameProp.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)

        val ageProp = rootType.properties.find { it.schemaName == "age" }.shouldNotBeNull()
        ageProp.nullable shouldBe true
        ageProp.type shouldBe TypeRef.Primitive(PrimitiveType.INT)
    }

    // ── Test 2: Enum type — SCREAMING_SNAKE_CASE constants ───────────────

    @Test
    fun `enum type produces EnumType with SCREAMING_SNAKE_CASE kotlin names`() {
        val model = parse(
            """
            {
              "definitions": {
                "Color": {
                  "type": "string",
                  "enum": ["red", "green-blue", "YELLOW"]
                }
              }
            }
            """
        )

        val enumType = model.types
            .filterIsInstance<TypeDefinition.EnumType>()
            .find { it.schemaName == "Color" }
            .shouldNotBeNull()

        enumType.values.shouldHaveSize(3)
        enumType.values.map { it.serializedValue } shouldBe listOf("red", "green-blue", "YELLOW")
        enumType.values.map { it.kotlinName } shouldBe listOf("RED", "GREEN_BLUE", "YELLOW")
    }

    // ── Test 3: $ref to definitions — TypeRef.Named, definition in model ──

    @Test
    fun `$ref property resolves to TypeRef_Named and adds referenced type to model`() {
        val model = parse(
            """
            {
              "type": "object",
              "title": "Root",
              "properties": {
                "pet": { "${'$'}ref": "#/definitions/Animal" }
              },
              "definitions": {
                "Animal": {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" }
                  }
                }
              }
            }
            """
        )

        // The Animal definition should be in the model
        val animalType = model.types
            .filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Animal" }
            .shouldNotBeNull()

        animalType.properties.find { it.schemaName == "name" }.shouldNotBeNull()

        // The root type should reference Animal
        val rootType = model.types
            .filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Root" }
            .shouldNotBeNull()

        val petProp = rootType.properties.find { it.schemaName == "pet" }.shouldNotBeNull()
        petProp.type shouldBe TypeRef.Named("Animal")
    }

    // ── Test 4: oneOf with boolean and $ref → UnionType ───────────────────

    @Test
    fun `oneOf boolean and ref produces UnionType with two variants`() {
        val model = parse(
            """
            {
              "definitions": {
                "StringOrBool": {
                  "oneOf": [
                    { "type": "boolean" },
                    { "${'$'}ref": "#/definitions/MyString" }
                  ]
                },
                "MyString": {
                  "type": "string"
                }
              }
            }
            """
        )

        val unionType = model.types
            .filterIsInstance<TypeDefinition.UnionType>()
            .find { it.schemaName == "StringOrBool" }
            .shouldNotBeNull()

        unionType.variants.shouldHaveSize(2)

        val boolVariant = unionType.variants.find { it.type == TypeRef.Primitive(PrimitiveType.BOOLEAN) }
        boolVariant.shouldNotBeNull()
        boolVariant.kotlinName shouldBe "BooleanVariant"

        val refVariant = unionType.variants.find { it.type == TypeRef.Named("Mystring") || it.type == TypeRef.Named("MyString") }
        refVariant.shouldNotBeNull()
    }

    // ── Test 5: Array property with $ref items → TypeRef.ListOf(Named) ───

    @Test
    fun `array property with ref items produces TypeRef_ListOf wrapping TypeRef_Named`() {
        val model = parse(
            """
            {
              "type": "object",
              "title": "Container",
              "properties": {
                "items": {
                  "type": "array",
                  "items": { "${'$'}ref": "#/definitions/Item" }
                }
              },
              "definitions": {
                "Item": {
                  "type": "object",
                  "properties": {
                    "id": { "type": "string" }
                  }
                }
              }
            }
            """
        )

        val rootType = model.types
            .filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Container" }
            .shouldNotBeNull()

        val itemsProp = rootType.properties.find { it.schemaName == "items" }.shouldNotBeNull()
        val listOf = itemsProp.type.shouldBeInstanceOf<TypeRef.ListOf>()
        listOf.element shouldBe TypeRef.Named("Item")

        // Item definition must be in the model
        model.types.map { it.schemaName } shouldContain "Item"
    }

    // ── Test 6: Hyphenated property name → camelCase, schemaName preserved ─

    @Test
    fun `hyphenated property name becomes camelCase kotlinName with original schemaName preserved`() {
        val model = parse(
            """
            {
              "type": "object",
              "properties": {
                "cancel-in-progress": { "type": "boolean" },
                "run_id":             { "type": "integer" }
              }
            }
            """
        )

        val rootType = model.types
            .filterIsInstance<TypeDefinition.ComplexType>()
            .first()

        val cancelProp = rootType.properties.find { it.schemaName == "cancel-in-progress" }.shouldNotBeNull()
        cancelProp.kotlinName shouldBe "cancelInProgress"
        cancelProp.type shouldBe TypeRef.Primitive(PrimitiveType.BOOLEAN)

        val runIdProp = rootType.properties.find { it.schemaName == "run_id" }.shouldNotBeNull()
        runIdProp.kotlinName shouldBe "runId"
        runIdProp.type shouldBe TypeRef.Primitive(PrimitiveType.INT)
    }

    // ── Additional tests ───────────────────────────────────────────────────

    @Test
    fun `source format is JSON_SCHEMA`() {
        val model = parse("""{ "type": "object" }""")
        model.sourceFormat shouldBe io.github.schema2class.core.ir.SourceFormat.JSON_SCHEMA
    }

    @Test
    fun `$id becomes namespace`() {
        val model = parse(
            """
            {
              "${'$'}id": "https://example.com/my-schema.json",
              "type": "object"
            }
            """
        )
        model.namespace shouldBe "https://example.com/my-schema.json"
    }

    @Test
    fun `description becomes documentation on type`() {
        val model = parse(
            """
            {
              "definitions": {
                "Color": {
                  "description": "A colour",
                  "type": "string",
                  "enum": ["red", "green"]
                }
              }
            }
            """
        )
        val enumType = model.types
            .filterIsInstance<TypeDefinition.EnumType>()
            .find { it.schemaName == "Color" }
            .shouldNotBeNull()
        enumType.documentation shouldBe "A colour"
    }

    @Test
    fun `property default value is preserved`() {
        val model = parse(
            """
            {
              "type": "object",
              "properties": {
                "timeout": { "type": "integer", "default": 30 }
              }
            }
            """
        )
        val rootType = model.types.filterIsInstance<TypeDefinition.ComplexType>().first()
        val prop = rootType.properties.find { it.schemaName == "timeout" }.shouldNotBeNull()
        prop.defaultValue shouldBe "30"
    }

    @Test
    fun `string constraints are captured`() {
        val model = parse(
            """
            {
              "definitions": {
                "ShortString": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 255,
                  "pattern": "^[a-z]+$"
                }
              }
            }
            """
        )
        val alias = model.types
            .filterIsInstance<TypeDefinition.AliasType>()
            .find { it.schemaName == "ShortString" }
            .shouldNotBeNull()
        alias.constraints shouldContain io.github.schema2class.core.ir.Constraint.MinLength(1)
        alias.constraints shouldContain io.github.schema2class.core.ir.Constraint.MaxLength(255)
        alias.constraints shouldContain io.github.schema2class.core.ir.Constraint.Pattern("^[a-z]+\$")
    }

    @Test
    fun `discriminator propertyName is captured on union types`() {
        val model = parse(
            """
            {
              "definitions": {
                "Pet": {
                  "oneOf": [
                    { "${'$'}ref": "#/definitions/Cat" },
                    { "${'$'}ref": "#/definitions/Dog" }
                  ],
                  "discriminator": { "propertyName": "petType" }
                },
                "Cat": { "type": "object", "properties": { "indoor": { "type": "boolean" } } },
                "Dog": { "type": "object", "properties": { "breed": { "type": "string" } } }
              }
            }
            """
        )
        val union = model.types
            .filterIsInstance<TypeDefinition.UnionType>()
            .find { it.schemaName == "Pet" }
            .shouldNotBeNull()
        union.discriminatorProperty shouldBe "petType"
    }

    @Test
    fun `defs keyword works same as definitions`() {
        val model = parse(
            """
            {
              "${'$'}defs": {
                "Fruit": {
                  "type": "string",
                  "enum": ["apple", "banana"]
                }
              }
            }
            """
        )
        model.types.map { it.schemaName } shouldContain "Fruit"
    }
}
