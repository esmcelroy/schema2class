package ca.esmcelroy.schema2class.parser.xsd

import ca.esmcelroy.schema2class.core.ir.Constraint
import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.SourceFormat
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class XsdParserTest {

    private val parser = XsdParser()

    private fun parse(xml: String) =
        parser.parse(xml.trimIndent().byteInputStream(), "com.example")

    // ── Test 1: source format ─────────────────────────────────────────────────

    @Test
    fun `source format is XSD`() {
        val model = parse("""<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>""")
        model.sourceFormat shouldBe SourceFormat.XSD
    }

    // ── Test 2: targetNamespace ───────────────────────────────────────────────

    @Test
    fun `targetNamespace becomes model namespace`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="urn:example:foo"/>
            """
        )
        model.namespace shouldBe "urn:example:foo"
    }

    @Test
    fun `doctype declarations are rejected`() {
        shouldThrowAny {
            parse(
                """
                <!DOCTYPE xs:schema [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:annotation>
                    <xs:documentation>&xxe;</xs:documentation>
                  </xs:annotation>
                </xs:schema>
                """,
            )
        }
    }

    // ── Test 3: simpleType with string enum ───────────────────────────────────

    @Test
    fun `simpleType with enumeration produces EnumType with SCREAMING_SNAKE_CASE constants`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="Color">
                <xs:restriction base="xs:string">
                  <xs:enumeration value="red"/>
                  <xs:enumeration value="green-blue"/>
                  <xs:enumeration value="YELLOW"/>
                </xs:restriction>
              </xs:simpleType>
            </xs:schema>
            """
        )
        val enum = model.types.filterIsInstance<TypeDefinition.EnumType>()
            .find { it.schemaName == "Color" }.shouldNotBeNull()
        enum.values.map { it.serializedValue } shouldBe listOf("red", "green-blue", "YELLOW")
        enum.values.map { it.kotlinName } shouldBe listOf("RED", "GREEN_BLUE", "YELLOW")
    }

    // ── Test 4: enum with ccts:Name annotation (UNECE pattern) ───────────────

    @Test
    fun `enum with ccts Name annotation uses name for kotlin constant`() {
        val model = parse(
            """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                        xmlns:ccts="urn:un:unece:uncefact:documentation:standard:CoreComponentsTechnicalSpecification:2">
              <xsd:simpleType name="ActionCodeContentType">
                <xsd:restriction base="xsd:token">
                  <xsd:enumeration value="1">
                    <xsd:annotation>
                      <xsd:documentation xml:lang="en">
                        <ccts:Name>Added</ccts:Name>
                        <ccts:Description>The information was added.</ccts:Description>
                      </xsd:documentation>
                    </xsd:annotation>
                  </xsd:enumeration>
                  <xsd:enumeration value="2">
                    <xsd:annotation>
                      <xsd:documentation xml:lang="en">
                        <ccts:Name>Deleted</ccts:Name>
                      </xsd:documentation>
                    </xsd:annotation>
                  </xsd:enumeration>
                </xsd:restriction>
              </xsd:simpleType>
            </xsd:schema>
            """
        )
        val enum = model.types.filterIsInstance<TypeDefinition.EnumType>()
            .find { it.schemaName == "ActionCodeContentType" }.shouldNotBeNull()
        enum.values.map { it.serializedValue } shouldBe listOf("1", "2")
        enum.values.map { it.kotlinName } shouldBe listOf("ADDED", "DELETED")
        enum.values[0].documentation shouldBe "The information was added."
    }

    // ── Test 5: complexType with sequence → ComplexType ───────────────────────

    @Test
    fun `complexType with sequence produces ComplexType`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Address">
                <xs:sequence>
                  <xs:element name="street" type="xs:string"/>
                  <xs:element name="postCode" type="xs:string" minOccurs="0"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Address" }.shouldNotBeNull()

        val street = type.properties.find { it.schemaName == "street" }.shouldNotBeNull()
        street.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        street.nullable shouldBe false
        street.kind shouldBe ca.esmcelroy.schema2class.core.ir.PropertyKind.ELEMENT

        val postCode = type.properties.find { it.schemaName == "postCode" }.shouldNotBeNull()
        postCode.nullable shouldBe true
    }

    // ── Test 6: maxOccurs="unbounded" → ListOf ────────────────────────────────

    @Test
    fun `element with maxOccurs unbounded produces ListOf property`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Order">
                <xs:sequence>
                  <xs:element name="item" type="xs:string" maxOccurs="unbounded"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Order" }.shouldNotBeNull()
        val itemProp = type.properties.find { it.schemaName == "item" }.shouldNotBeNull()
        itemProp.type shouldBe TypeRef.ListOf(TypeRef.Primitive(PrimitiveType.STRING))
        itemProp.nullable shouldBe false
    }

    // ── Test 7: xs:attribute use=required vs optional ─────────────────────────

    @Test
    fun `attribute with use=required is non-nullable, optional is nullable`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Tagged">
                <xs:attribute name="id" type="xs:string" use="required"/>
                <xs:attribute name="lang" type="xs:string" use="optional"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Tagged" }.shouldNotBeNull()
        val id = type.properties.find { it.schemaName == "id" }.shouldNotBeNull()
        id.nullable shouldBe false
        id.kind shouldBe ca.esmcelroy.schema2class.core.ir.PropertyKind.ATTRIBUTE
        type.properties.find { it.schemaName == "lang" }.shouldNotBeNull().nullable shouldBe true
    }

    // ── Test 8: xs:simpleContent + xs:extension → contentProperty ─────────────

    @Test
    fun `simpleContent extension produces ComplexType with contentProperty`() {
        val model = parse(
            """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <xsd:complexType name="AmountType">
                <xsd:simpleContent>
                  <xsd:extension base="xsd:decimal">
                    <xsd:attribute name="currencyID" type="xsd:token" use="optional"/>
                  </xsd:extension>
                </xsd:simpleContent>
              </xsd:complexType>
            </xsd:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "AmountType" }.shouldNotBeNull()

        val content = type.contentProperty.shouldNotBeNull()
        content.type shouldBe TypeRef.Primitive(PrimitiveType.DECIMAL)
        content.nullable shouldBe false
        content.kind shouldBe ca.esmcelroy.schema2class.core.ir.PropertyKind.CONTENT

        val currency = type.properties.find { it.schemaName == "currencyID" }.shouldNotBeNull()
        currency.nullable shouldBe true
        currency.kind shouldBe ca.esmcelroy.schema2class.core.ir.PropertyKind.ATTRIBUTE
    }

    // ── Test 9: simpleType restriction without enum → AliasType ──────────────

    @Test
    fun `simpleType restriction without enum produces AliasType with constraints`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="ShortString">
                <xs:restriction base="xs:string">
                  <xs:length value="8"/>
                  <xs:minLength value="1"/>
                  <xs:maxLength value="100"/>
                  <xs:pattern value="[a-z]+"/>
                  <xs:totalDigits value="12"/>
                  <xs:fractionDigits value="2"/>
                </xs:restriction>
              </xs:simpleType>
            </xs:schema>
            """
        )
        val alias = model.types.filterIsInstance<TypeDefinition.AliasType>()
            .find { it.schemaName == "ShortString" }.shouldNotBeNull()
        alias.aliasedType shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        alias.constraints shouldContain Constraint.ExactLength(8)
        alias.constraints shouldContain Constraint.MinLength(1)
        alias.constraints shouldContain Constraint.MaxLength(100)
        alias.constraints shouldContain Constraint.Pattern("[a-z]+")
        alias.constraints shouldContain Constraint.TotalDigits(12)
        alias.constraints shouldContain Constraint.FractionDigits(2)
    }

    @Test
    fun `element and attribute default and fixed values produce kotlin literals`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Defaults">
                <xs:sequence>
                  <xs:element name="name" type="xs:string" default="${'$'}{env}"/>
                  <xs:element name="count" type="xs:int" default="7"/>
                  <xs:element name="rate" type="xs:decimal" fixed="12.50"/>
                  <xs:element name="enabled" type="xs:boolean" fixed="1"/>
                  <xs:element name="when" type="xs:date" default="2026-07-08"/>
                </xs:sequence>
                <xs:attribute name="mode" type="xs:string" default="auto"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Defaults" }.shouldNotBeNull()

        type.properties.find { it.schemaName == "name" }.shouldNotBeNull()
            .defaultValue shouldBe "\"\\${'$'}{env}\""
        type.properties.find { it.schemaName == "count" }.shouldNotBeNull()
            .defaultValue shouldBe "7"
        type.properties.find { it.schemaName == "rate" }.shouldNotBeNull()
            .defaultValue shouldBe "java.math.BigDecimal(\"12.50\")"
        type.properties.find { it.schemaName == "enabled" }.shouldNotBeNull()
            .defaultValue shouldBe "true"
        type.properties.find { it.schemaName == "when" }.shouldNotBeNull()
            .defaultValue shouldBe "java.time.LocalDate.parse(\"2026-07-08\")"
        type.properties.find { it.schemaName == "mode" }.shouldNotBeNull()
            .defaultValue shouldBe "\"auto\""
    }

    @Test
    fun `simpleType list produces AliasType of ListOf item type`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="IntList">
                <xs:list itemType="xs:int"/>
              </xs:simpleType>
            </xs:schema>
            """
        )
        val alias = model.types.filterIsInstance<TypeDefinition.AliasType>()
            .find { it.schemaName == "IntList" }.shouldNotBeNull()

        alias.aliasedType shouldBe TypeRef.ListOf(TypeRef.Primitive(PrimitiveType.INT))
    }

    @Test
    fun `simpleType union with distinguishable members produces UnionType`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="FlagOrCount">
                <xs:union memberTypes="xs:boolean xs:int"/>
              </xs:simpleType>
            </xs:schema>
            """
        )
        val union = model.types.filterIsInstance<TypeDefinition.UnionType>()
            .find { it.schemaName == "FlagOrCount" }.shouldNotBeNull()

        union.variants.map { it.kotlinName } shouldBe listOf("BooleanVariant", "IntVariant")
        union.variants.map { it.type } shouldBe listOf(
            TypeRef.Primitive(PrimitiveType.BOOLEAN),
            TypeRef.Primitive(PrimitiveType.INT),
        )
    }

    @Test
    fun `simpleType union with string member degrades to String alias`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="TokenOrInt">
                <xs:union memberTypes="xs:string xs:int"/>
              </xs:simpleType>
            </xs:schema>
            """
        )
        val alias = model.types.filterIsInstance<TypeDefinition.AliasType>()
            .find { it.schemaName == "TokenOrInt" }.shouldNotBeNull()

        alias.aliasedType shouldBe TypeRef.Primitive(PrimitiveType.STRING)
    }

    @Test
    fun `inline simpleType list element references generated alias type`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Palette">
                <xs:sequence>
                  <xs:element name="colors">
                    <xs:simpleType>
                      <xs:list itemType="xs:string"/>
                    </xs:simpleType>
                  </xs:element>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val palette = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Palette" }.shouldNotBeNull()
        palette.properties.single { it.schemaName == "colors" }
            .type shouldBe TypeRef.Named("PaletteColors")
        val alias = model.types.filterIsInstance<TypeDefinition.AliasType>()
            .find { it.schemaName == "Palette_Colors" }.shouldNotBeNull()
        alias.aliasedType shouldBe TypeRef.ListOf(TypeRef.Primitive(PrimitiveType.STRING))
    }

    // ── Test 10: top-level element with inline complexType ────────────────────

    @Test
    fun `top-level element with inline complexType produces named ComplexType`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="shiporder">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="orderperson" type="xs:string"/>
                  </xs:sequence>
                  <xs:attribute name="orderid" type="xs:string" use="required"/>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "shiporder" }.shouldNotBeNull()
        type.properties.find { it.schemaName == "orderperson" }!!.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        type.properties.find { it.schemaName == "orderid" }!!.nullable shouldBe false
    }

    // ── Test 11: built-in type mapping ───────────────────────────────────────

    @Test
    fun `XSD built-in types map to correct PrimitiveType values`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Primitives">
                <xs:sequence>
                  <xs:element name="amount" type="xs:decimal"/>
                  <xs:element name="count" type="xs:integer"/>
                  <xs:element name="active" type="xs:boolean"/>
                  <xs:element name="link" type="xs:anyURI"/>
                  <xs:element name="photo" type="xs:base64Binary"/>
                  <xs:element name="width" type="xs:float"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Primitives" }.shouldNotBeNull()
        fun prop(name: String) = type.properties.find { it.schemaName == name }!!.type
        prop("amount") shouldBe TypeRef.Primitive(PrimitiveType.DECIMAL)
        prop("count") shouldBe TypeRef.Primitive(PrimitiveType.LONG)
        prop("active") shouldBe TypeRef.Primitive(PrimitiveType.BOOLEAN)
        prop("link") shouldBe TypeRef.Primitive(PrimitiveType.URI)
        prop("photo") shouldBe TypeRef.Primitive(PrimitiveType.BYTES)
        prop("width") shouldBe TypeRef.Primitive(PrimitiveType.FLOAT)
    }

    // ── Test 12: named type reference from element type attribute ─────────────

    @Test
    fun `element type attribute referencing named type produces TypeRef_Named`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Order">
                <xs:sequence>
                  <xs:element name="shipTo" type="Address"/>
                </xs:sequence>
              </xs:complexType>
              <xs:complexType name="Address">
                <xs:sequence>
                  <xs:element name="street" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val order = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Order" }.shouldNotBeNull()
        order.properties.find { it.schemaName == "shipTo" }!!.type shouldBe TypeRef.Named("Address")
    }

    // ── Test 13: hyphenated element name → camelCase kotlinName ──────────────

    @Test
    fun `hyphenated element name becomes camelCase kotlinName with schemaName preserved`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Person">
                <xs:sequence>
                  <xs:element name="first-name" type="xs:string"/>
                  <xs:element name="date_of_birth" type="xs:date"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val type = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Person" }.shouldNotBeNull()

        val firstNameProp = type.properties.find { it.schemaName == "first-name" }.shouldNotBeNull()
        firstNameProp.kotlinName shouldBe "firstName"

        val dobProp = type.properties.find { it.schemaName == "date_of_birth" }.shouldNotBeNull()
        dobProp.kotlinName shouldBe "dateOfBirth"
        dobProp.type shouldBe TypeRef.Primitive(PrimitiveType.DATE)
    }

    // ── Groups: xs:group / xs:attributeGroup / element refs / choice ─────────

    @Test
    fun `group ref inlines the group's elements into the referencing type`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:group name="auditFields">
                <xs:sequence>
                  <xs:element name="createdBy" type="xs:string"/>
                  <xs:element name="createdAt" type="xs:dateTime"/>
                </xs:sequence>
              </xs:group>
              <xs:complexType name="Record">
                <xs:sequence>
                  <xs:element name="id" type="xs:string"/>
                  <xs:group ref="auditFields"/>
                </xs:sequence>
              </xs:complexType>
              <xs:complexType name="Summary">
                <xs:group ref="auditFields"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val record = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Record" }.shouldNotBeNull()
        record.properties.map { it.schemaName } shouldBe listOf("id", "createdBy", "createdAt")

        // group as the complexType's direct content particle
        val summary = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Summary" }.shouldNotBeNull()
        summary.properties.map { it.schemaName } shouldBe listOf("createdBy", "createdAt")
    }

    @Test
    fun `attributeGroup ref inlines attributes including nested groups`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:attributeGroup name="core">
                <xs:attribute name="id" type="xs:ID"/>
              </xs:attributeGroup>
              <xs:attributeGroup name="i18n">
                <xs:attribute name="lang" type="xs:language"/>
                <xs:attributeGroup ref="core"/>
              </xs:attributeGroup>
              <xs:complexType name="Span">
                <xs:attributeGroup ref="i18n"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val span = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Span" }.shouldNotBeNull()
        span.properties.map { it.schemaName } shouldBe listOf("lang", "id")
    }

    @Test
    fun `choice members become nullable properties`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Payment">
                <xs:sequence>
                  <xs:element name="amount" type="xs:decimal"/>
                  <xs:choice>
                    <xs:element name="iban" type="xs:string"/>
                    <xs:element name="cardNumber" type="xs:string"/>
                  </xs:choice>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val payment = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Payment" }.shouldNotBeNull()

        payment.properties.map { it.schemaName } shouldBe listOf("amount", "iban", "cardNumber")
        payment.properties.find { it.schemaName == "amount" }!!.nullable shouldBe false
        payment.properties.find { it.schemaName == "iban" }!!.nullable shouldBe true
        payment.properties.find { it.schemaName == "cardNumber" }!!.nullable shouldBe true
    }

    @Test
    fun `whole-content choice maps to a UnionType with element-named variants`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="CardType">
                <xs:sequence>
                  <xs:element name="number" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
              <xs:complexType name="PaymentMethod">
                <xs:choice>
                  <xs:element name="iban" type="xs:string"/>
                  <xs:element name="card" type="CardType"/>
                </xs:choice>
              </xs:complexType>
            </xs:schema>
            """
        )
        val union = model.types.filterIsInstance<TypeDefinition.UnionType>()
            .find { it.schemaName == "PaymentMethod" }.shouldNotBeNull()

        union.variants.map { it.kotlinName } shouldBe listOf("IbanVariant", "CardVariant")
        union.variants[0].type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        union.variants[1].type shouldBe TypeRef.Named("CardType")
    }

    @Test
    fun `choice with attributes keeps the flattened nullable mapping`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Contact">
                <xs:choice>
                  <xs:element name="email" type="xs:string"/>
                  <xs:element name="phone" type="xs:string"/>
                </xs:choice>
                <xs:attribute name="preferred" type="xs:boolean"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val contact = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Contact" }.shouldNotBeNull()
        contact.properties.map { it.schemaName } shouldBe listOf("email", "phone", "preferred")
        contact.properties[0].nullable shouldBe true
    }

    @Test
    fun `self-referencing group terminates`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:group name="loop">
                <xs:sequence>
                  <xs:element name="label" type="xs:string"/>
                  <xs:group ref="loop"/>
                </xs:sequence>
              </xs:group>
              <xs:complexType name="Node">
                <xs:group ref="loop"/>
              </xs:complexType>
            </xs:schema>
            """
        )
        val node = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Node" }.shouldNotBeNull()
        node.properties.map { it.schemaName } shouldBe listOf("label")
    }

    @Test
    fun `element ref resolves name and type from the top-level declaration`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="description" type="xs:string"/>
              <xs:complexType name="Bean">
                <xs:sequence>
                  <xs:element ref="description" minOccurs="0"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val bean = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Bean" }.shouldNotBeNull()
        val desc = bean.properties.single()
        desc.schemaName shouldBe "description"
        desc.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        desc.nullable shouldBe true
    }

    // ── Inheritance: xs:extension / xs:restriction ────────────────────────────

    @Test
    fun `complexContent extension flattens base properties first with superType provenance`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Vehicle">
                <xs:sequence>
                  <xs:element name="make" type="xs:string"/>
                  <xs:element name="model" type="xs:string"/>
                </xs:sequence>
                <xs:attribute name="id" type="xs:string" use="required"/>
              </xs:complexType>
              <xs:complexType name="Car">
                <xs:complexContent>
                  <xs:extension base="Vehicle">
                    <xs:sequence>
                      <xs:element name="doors" type="xs:int"/>
                    </xs:sequence>
                  </xs:extension>
                </xs:complexContent>
              </xs:complexType>
            </xs:schema>
            """
        )
        val car = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Car" }.shouldNotBeNull()

        car.properties.map { it.schemaName } shouldBe listOf("make", "model", "id", "doors")
        car.properties.last().type shouldBe TypeRef.Primitive(PrimitiveType.INT)
        car.superType shouldBe TypeRef.Named("Vehicle")
    }

    @Test
    fun `complexContent extension works when base is declared later in the document`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Car">
                <xs:complexContent>
                  <xs:extension base="Vehicle">
                    <xs:sequence>
                      <xs:element name="doors" type="xs:int"/>
                    </xs:sequence>
                  </xs:extension>
                </xs:complexContent>
              </xs:complexType>
              <xs:complexType name="Vehicle">
                <xs:sequence>
                  <xs:element name="make" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        val car = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Car" }.shouldNotBeNull()
        car.properties.map { it.schemaName } shouldBe listOf("make", "doors")
    }

    @Test
    fun `complexContent restriction uses its own declared subset without inheritance`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Vehicle">
                <xs:sequence>
                  <xs:element name="make" type="xs:string"/>
                  <xs:element name="model" type="xs:string"/>
                  <xs:element name="vin" type="xs:string" minOccurs="0"/>
                </xs:sequence>
              </xs:complexType>
              <xs:complexType name="RegisteredVehicle">
                <xs:complexContent>
                  <xs:restriction base="Vehicle">
                    <xs:sequence>
                      <xs:element name="make" type="xs:string"/>
                      <xs:element name="vin" type="xs:string"/>
                    </xs:sequence>
                  </xs:restriction>
                </xs:complexContent>
              </xs:complexType>
            </xs:schema>
            """
        )
        val restricted = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "RegisteredVehicle" }.shouldNotBeNull()

        // Restriction re-declares the kept content: exactly what is listed, no more
        restricted.properties.map { it.schemaName } shouldBe listOf("make", "vin")
        restricted.properties[1].nullable shouldBe false
    }

    @Test
    fun `simpleContent extension of another simpleContent type inherits content and attributes`() {
        val model = parse(
            """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <xsd:complexType name="CodeType">
                <xsd:simpleContent>
                  <xsd:extension base="xsd:token">
                    <xsd:attribute name="listID" type="xsd:token" use="optional"/>
                  </xsd:extension>
                </xsd:simpleContent>
              </xsd:complexType>
              <xsd:complexType name="CurrencyCodeType">
                <xsd:simpleContent>
                  <xsd:restriction base="CodeType">
                    <xsd:attribute name="listAgencyID" type="xsd:token" use="optional"/>
                  </xsd:restriction>
                </xsd:simpleContent>
              </xsd:complexType>
            </xsd:schema>
            """
        )
        val currency = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "CurrencyCodeType" }.shouldNotBeNull()

        // contentProperty inherited from CodeType via the flattener
        currency.contentProperty.shouldNotBeNull().type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        currency.properties.map { it.schemaName } shouldBe listOf("listID", "listAgencyID")
    }

    // ── Tests 14-16: package derivation from targetNamespace ─────────────────

    @Test
    fun `parse without packageName derives package from targetNamespace`() {
        val model = parser.parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="urn:test:business-doc"/>
            """.trimIndent().byteInputStream(),
        )
        model.packageName shouldBe "test.business_doc"
    }

    @Test
    fun `parse without packageName and without targetNamespace uses default package`() {
        val model = parser.parse(
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>""".byteInputStream(),
        )
        model.packageName shouldBe "generated"
    }

    @Test
    fun `parse with configured mapper applies basePackage and overrides`() {
        val mapper = ca.esmcelroy.schema2class.core.naming.NamespacePackageMapper(
            basePackage = "com.corp.gen",
        )
        val model = parser.parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="http://maven.apache.org/POM/4.0.0"/>
            """.trimIndent().byteInputStream(),
            mapper,
        )
        model.packageName shouldBe "com.corp.gen.org.apache.maven.pom._4_0_0"
    }

    // ── Test 17: inline nested complexType creates named type ─────────────────

    @Test
    fun `inline nested complexType inside element creates named type in model`() {
        val model = parse(
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Order">
                <xs:sequence>
                  <xs:element name="item" maxOccurs="unbounded">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="title" type="xs:string"/>
                        <xs:element name="price" type="xs:decimal"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:sequence>
              </xs:complexType>
            </xs:schema>
            """
        )
        // The inline type should be registered with a generated name
        val inlineType = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.properties.any { p -> p.schemaName == "price" } }.shouldNotBeNull()
        inlineType.properties.find { it.schemaName == "title" }!!.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)

        // Order should reference the inline type as a list
        val order = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "Order" }.shouldNotBeNull()
        val itemProp = order.properties.find { it.schemaName == "item" }.shouldNotBeNull()
        itemProp.type.shouldBeInstanceOf<TypeRef.ListOf>()
    }
}
