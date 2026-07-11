package ca.esmcelroy.schema2class.core.naming

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NamingBindingsTest {

    @Test
    fun `binding lines split type and property overrides with comments`() {
        val bindings = NamingBindings.fromLines(
            listOf(
                "# consumer-local generated name bindings",
                "Payload = FriendlyPayload",
                "FriendlyPayload.rg = region # short wire name",
            ),
        )

        bindings.typeName("Payload") shouldBe "FriendlyPayload"
        bindings.propertyName("Payload", "FriendlyPayload", "rg") shouldBe "region"
    }
}
