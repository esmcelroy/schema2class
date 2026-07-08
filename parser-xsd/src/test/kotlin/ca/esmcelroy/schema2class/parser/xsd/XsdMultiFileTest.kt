package ca.esmcelroy.schema2class.parser.xsd

import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

class XsdMultiFileTest {

    private val parser = XsdParser()

    private fun fixture(name: String): File =
        File(javaClass.getResource("/multifile/$name").shouldNotBeNull().toURI())

    private fun complexType(
        models: List<ca.esmcelroy.schema2class.core.ir.SchemaModel>,
        namespace: String,
        kotlinName: String,
    ): TypeDefinition.ComplexType =
        models.first { it.namespace == namespace }
            .types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == kotlinName }
            .shouldNotBeNull()

    // ── Basic import + include resolution ─────────────────────────────────────

    @Test
    fun `parseWithImports returns one model per namespace, entry namespace first`() {
        val models = parser.parseWithImports(fixture("catalog.xsd"))

        models.map { it.namespace } shouldContainExactly
            listOf("urn:test:catalog", "urn:test:money")
        models[0].packageName shouldBe "test.catalog"
        models[1].packageName shouldBe "test.money"
    }

    @Test
    fun `chameleon include splices types into the including namespace`() {
        val models = parser.parseWithImports(fixture("catalog.xsd"))
        val catalogTypes = models.first { it.namespace == "urn:test:catalog" }
            .types.map { it.kotlinName }

        catalogTypes shouldContainExactly listOf("CatalogType", "ItemType")
    }

    @Test
    fun `same-namespace reference to an included type stays package-local`() {
        val models = parser.parseWithImports(fixture("catalog.xsd"))
        val catalog = complexType(models, "urn:test:catalog", "CatalogType")

        val itemProp = catalog.properties.find { it.schemaName == "item" }.shouldNotBeNull()
        val list = itemProp.type.shouldBeInstanceOf<TypeRef.ListOf>()
        list.element shouldBe TypeRef.Named("ItemType")
    }

    @Test
    fun `cross-namespace references carry the imported namespace package`() {
        val models = parser.parseWithImports(fixture("catalog.xsd"))

        val catalog = complexType(models, "urn:test:catalog", "CatalogType")
        catalog.properties.find { it.schemaName == "totalValue" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("MoneyType", "test.money")

        // Reference declared inside the chameleon document resolves the same way
        val item = complexType(models, "urn:test:catalog", "ItemType")
        item.properties.find { it.schemaName == "price" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("MoneyType", "test.money")
    }

    @Test
    fun `imported document is parsed once and produces its own model`() {
        val models = parser.parseWithImports(fixture("catalog.xsd"))
        val money = models.first { it.namespace == "urn:test:money" }

        // money.xsd is imported by both catalog.xsd and catalog-items.xsd —
        // the cache must dedupe it to a single MoneyType
        money.types shouldHaveSize 1
        val moneyType = money.types.single().shouldBeInstanceOf<TypeDefinition.ComplexType>()
        moneyType.contentProperty.shouldNotBeNull()
            .type shouldBe TypeRef.Primitive(PrimitiveType.DECIMAL)
        moneyType.properties.single().schemaName shouldBe "currency"
        moneyType.properties.single().nullable shouldBe false
    }

    // ── Cycles ────────────────────────────────────────────────────────────────

    @Test
    fun `circular imports terminate and resolve references in both directions`() {
        val models = parser.parseWithImports(fixture("cycle-a.xsd"))

        models.map { it.namespace } shouldContainExactly
            listOf("urn:test:cycle-a", "urn:test:cycle-b")

        val aType = complexType(models, "urn:test:cycle-a", "AType")
        aType.properties.find { it.schemaName == "partner" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("BType", "test.cycle_b")

        val bType = complexType(models, "urn:test:cycle-b", "BType")
        bType.properties.find { it.schemaName == "owner" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("AType", "test.cycle_a")
    }

    // ── Degraded inputs ───────────────────────────────────────────────────────

    @Test
    fun `missing import location warns but still yields the entry model with stable packages`() {
        val models = parser.parseWithImports(fixture("broken.xsd"))

        models shouldHaveSize 1
        models[0].namespace shouldBe "urn:test:broken"

        val broken = complexType(models, "urn:test:broken", "BrokenType")
        // The missing namespace was declared on xs:import, so refs into it
        // still get a deterministic derived package
        broken.properties.find { it.schemaName == "ghost" }.shouldNotBeNull()
            .type shouldBe TypeRef.Named("GhostType", "test.missing")
    }
}
