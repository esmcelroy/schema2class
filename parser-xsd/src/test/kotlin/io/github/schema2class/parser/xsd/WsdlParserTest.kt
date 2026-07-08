package io.github.schema2class.parser.xsd

import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.github.schema2class.core.naming.NamespacePackageMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WsdlParserTest {

    private val parser = WsdlParser()

    @Test
    fun `wsdl types schemas parse into namespace models`() {
        val models = parser.parse(
            """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <wsdl:types>
                <xs:schema targetNamespace="urn:test:customer">
                  <xs:complexType name="Customer">
                    <xs:sequence>
                      <xs:element name="id" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                <xs:schema targetNamespace="urn:test:order"
                           xmlns:cust="urn:test:customer">
                  <xs:complexType name="Order">
                    <xs:sequence>
                      <xs:element name="customer" type="cust:Customer"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
              </wsdl:types>
            </wsdl:definitions>
            """.trimIndent().byteInputStream(),
            NamespacePackageMapper(),
        )

        models shouldHaveSize 2
        val customer = models.single { it.namespace == "urn:test:customer" }
        customer.packageName shouldBe "test.customer"
        customer.types.filterIsInstance<TypeDefinition.ComplexType>()
            .single { it.schemaName == "Customer" }
            .properties.single { it.schemaName == "id" }
            .type shouldBe TypeRef.Primitive(PrimitiveType.STRING)

        val order = models.single { it.namespace == "urn:test:order" }
        order.packageName shouldBe "test.order"
        val customerProp = order.types.filterIsInstance<TypeDefinition.ComplexType>()
            .single { it.schemaName == "Order" }
            .properties.single { it.schemaName == "customer" }
        customerProp.type shouldBe TypeRef.Named("Customer", packageName = "test.customer")
    }

    @Test
    fun `wsdl with no embedded schemas fails clearly`() {
        val error = shouldThrow<IllegalArgumentException> {
            parser.parse(
                """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/">
                  <wsdl:types/>
                </wsdl:definitions>
                """.trimIndent().byteInputStream(),
            )
        }

        error.message.shouldNotBeNull() shouldBe "WSDL document contains no xs:schema children under wsdl:types"
    }

    @Test
    fun `doctype declarations are rejected`() {
        shouldThrowAny {
            parser.parse(
                """
                <!DOCTYPE wsdl:definitions [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <wsdl:types>
                    <xs:schema>
                      <xs:annotation>
                        <xs:documentation>&xxe;</xs:documentation>
                      </xs:annotation>
                    </xs:schema>
                  </wsdl:types>
                </wsdl:definitions>
                """.trimIndent().byteInputStream(),
            )
        }
    }
}
