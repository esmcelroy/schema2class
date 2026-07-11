package ca.esmcelroy.schema2class.parser.jsonschema

import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef
import ca.esmcelroy.schema2class.core.naming.NamingBindings
import ca.esmcelroy.schema2class.core.naming.NamingStrategy
import ca.esmcelroy.schema2class.core.naming.NamingTarget
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Friendly-name resolution (schema2class-edg): precedence between programmatic
 * strategy, sidecar bindings, x-object-* extensions, and title conventions.
 */
class JsonSchemaNamingTest {

    private val parser = JsonSchemaParser()

    private fun parse(json: String) =
        parser.parse(json.trimIndent().byteInputStream(), "com.example")

    @Test
    fun `titles provide sanitized type and property names with array item pluralization`() {
        val model = parse(
            """
            {
              "definitions": {
                "Payload": {
                  "title": "Traffic Payload.",
                  "type": "object",
                  "properties": {
                    "rg": { "type": "integer", "title": "region code." },
                    "sl": {
                      "type": "array",
                      "items": {
                        "title": "IntersectionState",
                        "type": "object",
                        "properties": { "id": { "type": "string" } }
                      }
                    },
                    "is": {
                      "title": "IntersectionStatusObject",
                      "type": "object",
                      "properties": { "raw": { "type": "string" } }
                    },
                    "class": { "type": "string", "title": "class" },
                    "a": { "type": "string", "title": "same name" },
                    "b": { "type": "string", "title": "same-name" }
                  }
                }
              }
            }
            """
        )

        val payload = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Payload" }.shouldNotBeNull()
        payload.kotlinName shouldBe "TrafficPayload"
        payload.properties.map { it.kotlinName } shouldBe listOf(
            "regionCode",
            "intersectionStates",
            "intersectionStatus",
            "classValue",
            "sameName",
            "sameName2",
        )
        payload.properties.find { it.schemaName == "sl" }!!.type shouldBe
            TypeRef.ListOf(TypeRef.Named("IntersectionState"))
        payload.properties.find { it.schemaName == "is" }!!.type shouldBe
            TypeRef.Named("IntersectionStatusObject")
    }

    @Test
    fun `sidecar naming bindings override generated type and property names`() {
        val parser = JsonSchemaParser(
            namingBindings = NamingBindings.fromLines(
                listOf(
                    "Payload = FriendlyPayload",
                    "FriendlyPayload.rg = region",
                ),
            ),
        )
        val model = parser.parse(
            """
            {
              "definitions": {
                "Payload": {
                  "type": "object",
                  "properties": {
                    "rg": { "type": "integer", "title": "region code." }
                  }
                }
              }
            }
            """.trimIndent().byteInputStream(),
            "com.example",
        )

        val payload = model.types.filterIsInstance<TypeDefinition.ComplexType>().single()
        payload.kotlinName shouldBe "FriendlyPayload"
        payload.properties.single().kotlinName shouldBe "region"
    }

    @Test
    fun `programmatic naming strategy wins over bindings and title conventions`() {
        val parser = JsonSchemaParser(
            namingStrategy = NamingStrategy { ctx ->
                when {
                    ctx.target == NamingTarget.TYPE && ctx.schemaName == "Payload" -> "StrategyPayload"
                    ctx.target == NamingTarget.PROPERTY && ctx.schemaName == "rg" -> "strategyRegion"
                    else -> null
                }
            },
            namingBindings = NamingBindings.fromLines(
                listOf(
                    "Payload = BoundPayload",
                    "StrategyPayload.rg = boundRegion",
                ),
            ),
        )
        val model = parser.parse(
            """
            {
              "definitions": {
                "Payload": {
                  "title": "TitledPayload",
                  "type": "object",
                  "properties": {
                    "rg": { "type": "integer", "title": "region code." }
                  }
                }
              }
            }
            """.trimIndent().byteInputStream(),
            "com.example",
        )

        val payload = model.types.filterIsInstance<TypeDefinition.ComplexType>().single()
        payload.kotlinName shouldBe "StrategyPayload"
        payload.properties.single().kotlinName shouldBe "strategyRegion"
    }
}
