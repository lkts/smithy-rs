/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.smithy.aws.reterminus.Endpoint
import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Literal
import software.amazon.smithy.aws.reterminus.lang.rule.Rule
import software.amazon.smithy.aws.testutil.TestDiscovery
import software.amazon.smithy.aws.testutil.TestDiscovery.RulesTestSuite
import software.amazon.smithy.model.SourceLocation
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest
import java.util.stream.Stream

internal class EndpointRuleGeneratorTest {
    companion object {
        @JvmStatic
        fun testSuites(): Stream<TestDiscovery.RulesTestSuite> = TestDiscovery().testSuites()
    }

    @Test
    fun generateEndpoints() {
        val endpoint = Endpoint.builder().url(Expr.of("https://{Region}.amazonaws.com"))
            .addHeader("x-amz-test", listOf(Literal.of("header-value")))
            .addAuthScheme(
                "sigv4",
                hashMapOf("signingName" to Literal.of("service"), "signingScope" to Literal.of("{Region}"))
            )
            .build()
        val generator = EndpointRuleGenerator(TestRuntimeConfig)
        TestWorkspace.testProject().unitTest {
            rustTemplate(
                """
                // create a local for region to generate against
                let region = "us-east-1";
                let endpoint = #{endpoint:W};

                """,
                "endpoint" to generator.generateEndpoint(
                    endpoint,
                    Scope.empty().withMember("region", Expr.ref(Identifier.of("region"), SourceLocation.none()))
                )
            )
        }.compileAndTest()
    }

    @Test
    fun `generate a basic rule`() {
        val rule = Rule.builder().condition(Literal.of(true).eq(true)).error("an error was returned!")
        rule.typecheck(software.amazon.smithy.aws.reterminus.eval.Scope())
        val generator = EndpointRuleGenerator(TestRuntimeConfig)
        TestWorkspace.testProject().unitTest {
            rustTemplate(
                """
                fn endpoint_resolver() -> Result<#{Endpoint}, #{Error}> {
                    #{rule:W}
                    todo!()
                }
                """,
                "rule" to generator.generateRule(rule, Scope.empty()),
                "Endpoint" to TestRuntimeConfig.endpoint(),
                "Error" to TestRuntimeConfig.endpointError()
            )
        }.compileAndTest()
    }

    @Test
    fun `generate a ruleset`() {
        TestWorkspace.testProject().unitTest {
            rustTemplate(
                "let _ = #{ruleset}(&#{Params}::builder().region(\"us-east-1\".to_string()).build().unwrap());",
                "ruleset" to EndpointRuleGenerator(TestRuntimeConfig).rulesetResolver(TestRuleset),
                "Params" to EndpointParamsGenerator(TestRuleset).paramsStruct()
            )
        }.compileAndTest()
    }

    @ParameterizedTest()
    @MethodSource("testSuites")
    fun `generate all rulesets`(suite: RulesTestSuite) {
        val project = TestWorkspace.testProject()
        project.lib { writer ->
            val ruleset = EndpointRuleGenerator(TestRuntimeConfig).rulesetResolver(suite.ruleset)
            val params = EndpointParamsGenerator(suite.ruleset).paramsStruct()
            suite.testSuites.forEach { testSuite ->
                val testGenerator = EndpointTestGenerator(testSuite, paramsType = params, resolverType = ruleset, suite.ruleset.parameters, TestRuntimeConfig)
                testGenerator.generate()(writer)
            }
        }
        project.compileAndTest()
    }
}
