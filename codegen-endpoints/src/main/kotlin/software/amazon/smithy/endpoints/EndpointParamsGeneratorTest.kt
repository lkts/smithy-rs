/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.testutil.TestDiscovery
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest
import java.util.stream.Stream

internal class EndpointParamsGeneratorTest {
    companion object {
        @JvmStatic
        fun testSuites(): Stream<TestDiscovery.RulesTestSuite> = TestDiscovery().testSuites()
    }

    @ParameterizedTest()
    @MethodSource("testSuites")
    fun `generate endpoint params for provided test suites`(testSuite: TestDiscovery.RulesTestSuite) {
        val project = TestWorkspace.testProject()
        project.lib { writer ->
            EndpointParamsGenerator(testSuite.ruleset).generateEndpointsStruct(writer)
        }
        project.compileAndTest()
    }

    private val ruleset = """{
        "version": "1.1",
        "serviceId": "minimal",
        "parameters": {
          "Region": {
            "type": "string",
            "builtIn": "AWS::Region",
            "required": true,
            "documentation": "The AWS region to send this request to"
          },
          "DisableHttp": {
              "type": "Boolean",
              "documentation": "Disallow requests from being sent over HTTP"
          },
          "DefaultTrue": {
            "type": "Boolean",
            "builtIn": "AWS::DefaultTrue",
            "required": true,
            "default": true
          }
        },
        "rules": [
          {
            "documentation": "base rule",
            "conditions": [
            ],
            "error": "empty ruleset"

          }
        ]
        }
    """.toRuleset()

    @Language("JSON")
    fun String.toRuleset(): EndpointRuleset {
        return EndpointRuleset.fromNode(Node.parse(this))
    }

    @Test
    fun `generate a struct`() {
        val project = TestWorkspace.testProject()
        val params = EndpointParamsGenerator(ruleset)
        project.lib { writer ->
            writer.unitTest("valid_builder") {
                rustTemplate(
                    """
                    let params = #{Params}::builder()
                        .region(String::from("us-east-1"))
                        .disable_http(true)
                        .build()
                        .expect("builder was valid");
                    assert_eq!(params.region().unwrap(), "us-east-1");
                    assert_eq!(params.default_true, true);
                    """,
                    "Params" to params.paramsStruct()
                )
            }

            writer.unitTest("invalid") {
                rustTemplate(
                    """
                    let _params = #{Params}::builder().build().expect_err("region was not specified");
                    """,
                    "Params" to params.paramsStruct()
                )
            }
        }
        project.compileAndTest()
    }
}
