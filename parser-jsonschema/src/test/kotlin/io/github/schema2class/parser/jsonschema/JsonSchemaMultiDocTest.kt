package io.github.schema2class.parser.jsonschema

import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class JsonSchemaMultiDocTest {

    private val parser = JsonSchemaParser()

    private fun fixture(name: String): File =
        File(javaClass.getResource("/multidoc/$name").shouldNotBeNull().toURI())

    private fun complexType(
        models: List<io.github.schema2class.core.ir.SchemaModel>,
        pkg: String,
        kotlinName: String,
    ): TypeDefinition.ComplexType =
        models.first { it.packageName == pkg }
            .types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == kotlinName }
            .shouldNotBeNull()

    @Test
    fun `external refs produce one model per document, entry first`() {
        val models = parser.parseWithRefs(fixture("order.schema.json"), "com.shop")

        models.map { it.packageName } shouldContainExactly
            listOf("com.shop", "com.shop.common", "com.shop.customer")
    }

    @Test
    fun `pointer ref into an external document is a cross-package Named ref`() {
        val models = parser.parseWithRefs(fixture("order.schema.json"), "com.shop")

        val order = complexType(models, "com.shop", "Order")
        order.properties.find { it.schemaName == "billing" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("Address", "com.shop.common")

        // The shared Address type is generated exactly once, in common's package
        val commonTypes = models.first { it.packageName == "com.shop.common" }.types
        commonTypes.count { it.kotlinName == "Address" } shouldBe 1
    }

    @Test
    fun `whole-document ref resolves to the external document's root type`() {
        val models = parser.parseWithRefs(fixture("order.schema.json"), "com.shop")

        val order = complexType(models, "com.shop", "Order")
        order.properties.find { it.schemaName == "customer" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("Customer", "com.shop.customer")

        // customer.schema.json's own external ref also resolved
        val customer = complexType(models, "com.shop.customer", "Customer")
        customer.properties.find { it.schemaName == "homeAddress" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("Address", "com.shop.common")
    }

    @Test
    fun `cross-document ref cycles terminate with refs in both directions`() {
        val models = parser.parseWithRefs(fixture("cycle-a.schema.json"), "com.graph")

        models shouldHaveSize 2

        val a = complexType(models, "com.graph", "NodeA")
        a.properties.find { it.schemaName == "peer" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("NodeB", "com.graph.cycle_b")

        val b = complexType(models, "com.graph.cycle_b", "NodeB")
        b.properties.find { it.schemaName == "back" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("NodeA", "com.graph")
    }

    @Test
    fun `single-document parse still degrades external refs with a warning`() {
        val model = parser.parse(fixture("order.schema.json"), "com.single")

        val order = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == "Order" }.shouldNotBeNull()
        // No resolver: named ref with null package, nothing external parsed
        order.properties.find { it.schemaName == "billing" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("Address")
        model.types.none { it.kotlinName == "Customer" } shouldBe true
    }
}
