package io.github.schema2class.core.naming

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NamespacePackageMapperTest {

    private val mapper = NamespacePackageMapper()

    // ── http(s) URIs: reverse-domain + path ───────────────────────────────────

    @Test
    fun `http URI maps to reverse-domain package with path segments`() {
        mapper.toPackage("http://maven.apache.org/POM/4.0.0") shouldBe
            "org.apache.maven.pom._4_0_0"
    }

    @Test
    fun `leading www is dropped and port ignored`() {
        mapper.toPackage("https://www.example.com:8443/schemas/order") shouldBe
            "com.example.schemas.order"
    }

    @Test
    fun `w3 XMLSchema namespace maps like JAXB`() {
        mapper.toPackage("http://www.w3.org/2001/XMLSchema") shouldBe
            "org.w3._2001.xmlschema"
    }

    @Test
    fun `trailing schema file extension is stripped`() {
        mapper.toPackage("http://example.com/schemas/common.xsd") shouldBe
            "com.example.schemas.common"
    }

    @Test
    fun `host-only URI maps to reversed host`() {
        mapper.toPackage("http://example.com") shouldBe "com.example"
    }

    // ── urn URIs: ordered segments, no reversal ───────────────────────────────

    @Test
    fun `urn URI maps to ordered sanitized segments`() {
        mapper.toPackage("urn:un:unece:uncefact:codelist:standard:UNECE:ActionCode:D24A") shouldBe
            "un.unece.uncefact.codelist.standard.unece.actioncode.d24a"
    }

    @Test
    fun `urn with hyphens sanitizes to underscores`() {
        mapper.toPackage("urn:test:business-doc") shouldBe "test.business_doc"
    }

    // ── Sanitization rules ────────────────────────────────────────────────────

    @Test
    fun `kotlin hard keywords get underscore suffix`() {
        mapper.toPackage("http://example.com/fun/object") shouldBe
            "com.example.fun_.object_"
    }

    @Test
    fun `digit-leading segments get underscore prefix`() {
        mapper.toPackage("urn:2023:spec") shouldBe "_2023.spec"
    }

    // ── Null, overrides, base package ─────────────────────────────────────────

    @Test
    fun `null namespace falls back to defaultPackage`() {
        mapper.toPackage(null) shouldBe "generated"
    }

    @Test
    fun `null namespace prefers basePackage when configured`() {
        NamespacePackageMapper(basePackage = "com.corp.gen").toPackage(null) shouldBe
            "com.corp.gen"
    }

    @Test
    fun `basePackage is prepended to derived packages`() {
        NamespacePackageMapper(basePackage = "com.corp.gen")
            .toPackage("urn:test:doc") shouldBe "com.corp.gen.test.doc"
    }

    @Test
    fun `override wins and is used verbatim without basePackage prefix`() {
        val m = NamespacePackageMapper(
            basePackage = "com.corp.gen",
            overrides = mapOf("urn:test:doc" to "com.corp.contracts.doc"),
        )
        m.toPackage("urn:test:doc") shouldBe "com.corp.contracts.doc"
    }

    // ── Collision avoidance ───────────────────────────────────────────────────

    @Test
    fun `colliding namespaces get deterministic numeric suffixes`() {
        val result = mapper.toPackages(
            listOf("urn:test:a-b", "urn:test:a_b", "urn:test:a.b"),
        )
        result["urn:test:a-b"] shouldBe "test.a_b"
        result["urn:test:a_b"] shouldBe "test.a_b_2"
        result["urn:test:a.b"] shouldBe "test.a_b_3"
    }

    @Test
    fun `non-colliding namespaces are unaffected by toPackages`() {
        val result = mapper.toPackages(listOf("urn:test:one", "urn:test:two", null))
        result["urn:test:one"] shouldBe "test.one"
        result["urn:test:two"] shouldBe "test.two"
        result[null] shouldBe "generated"
    }
}
