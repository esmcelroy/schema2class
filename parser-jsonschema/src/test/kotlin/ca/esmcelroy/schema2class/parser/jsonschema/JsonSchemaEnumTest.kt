package ca.esmcelroy.schema2class.parser.jsonschema

import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonSchemaEnumTest {

    private val parser = JsonSchemaParser()

    private fun parse(json: String) =
        parser.parse(json.trimIndent().byteInputStream(), "com.example")

    @Test
    fun `integer enum keeps numeric base type and values`() {
        val model = parse(
            """
            {
              "definitions": {
                "BulbState": {
                  "type": "integer",
                  "enum": [0, 1, 3]
                }
              }
            }
            """
        )

        val enumType = model.types
            .filterIsInstance<TypeDefinition.EnumType>()
            .find { it.schemaName == "BulbState" }
            .shouldNotBeNull()

        enumType.baseType shouldBe PrimitiveType.INT
        enumType.values.map { it.serializedValue } shouldBe listOf("0", "1", "3")
    }
}
