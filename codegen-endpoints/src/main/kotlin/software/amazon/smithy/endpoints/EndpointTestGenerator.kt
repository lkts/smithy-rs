/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.EndpointTest
import software.amazon.smithy.aws.reterminus.EndpointTestSuite
import software.amazon.smithy.aws.reterminus.eval.Value
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameters
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustType
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.docs
import software.amazon.smithy.rust.codegen.rustlang.join
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.util.dq

class EndpointTestGenerator(
    private val endpointTest: EndpointTestSuite,
    private val paramsType: RuntimeType,
    private val resolverType: RuntimeType,
    private val params: Parameters,
    runtimeConfig: RuntimeConfig
) {
    private val codegenScope = arrayOf(
        "Endpoint" to runtimeConfig.endpoint(),
        "Error" to runtimeConfig.endpointError(),
        "Document" to CargoDependency.SmithyTypes(runtimeConfig).asType().member("Document"),
        "HashMap" to RustType.HashMap.RuntimeType
    )

    fun generate(): Writable = writable {
        var id = 0
        endpointTest.testCases.forEach { testCase ->
            id += 1

            testCase.documentation?.also { docs(it) }
            rustTemplate(
                """
                /// From: ${testCase.sourceLocation.filename}:${testCase.sourceLocation.line}
                #{docs:W}
                ##[test]
                fn test_$id() {
                    let params = #{params:W};
                    let endpoint = #{resolver}(&params);
                    #{assertion:W}
                }
                """,
                "docs" to writable { docs(testCase.documentation ?: "no docs") },
                "params" to params(testCase),
                "resolver" to resolverType,
                "assertion" to writable {
                    when (val ex = testCase.expectation) {
                        is EndpointTest.Expectation.Endpoint -> {
                            rustTemplate(
                                """
                                let endpoint = endpoint.expect("Expected valid endpoint: ${ex.endpoint.url}");
                                assert_eq!(endpoint, #{expected:W});
                                """,
                                *codegenScope, "expected" to generateValue(ex.endpoint)
                            )
                        }
                        is EndpointTest.Expectation.Error -> {
                            rustTemplate(
                                """
                                let error = endpoint.expect_err("expected error ${ex.message} ${testCase.documentation}");
                                assert_eq!(error, #{Error}::msg(${ex.message.dq()}));
                                """,
                                *codegenScope
                            )
                        }
                    }
                }
            )
        }
    }

    private fun params(testCase: EndpointTest) = writable {
        rust("#T::builder()", paramsType)
        testCase.params.forEach { param ->
            val id = param.left
            val value = param.right
            if (params.get(id).isPresent) {
                rust(".${id.rustName()}(#W)", generateValue(value))
            }
        }
        rust(""".build().expect("invalid params")""")
    }

    private fun generateValue(value: Value): Writable {
        return {
            when (value) {
                is Value.Str -> rust(value.value().dq() + ".to_string()")
                is Value.Bool -> rust(value.toString())
                is Value.Array -> {
                    rust(
                        "vec![#W]",
                        join(
                            value.values.map { member ->
                                writable {
                                    rustTemplate(
                                        "#{Document}::from(#{value:W})",
                                        *codegenScope,
                                        "value" to generateValue(member)
                                    )
                                }
                            },
                            ", "
                        )
                    )
                }
                is Value.Record ->
                    rustBlock("") {
                        rustTemplate(
                            "let mut out = #{HashMap}::<String, #{Document}>::new();",
                            *codegenScope
                        )
                        value.forEach { identifier, v ->
                            rust(
                                "out.insert(${identifier.toString().dq()}.to_string(), #W.into());",
                                // When writing into the hashmap, it always needs to be an owned type
                                generateValue(v)
                            )
                        }
                        rustTemplate("out")
                    }

                is Value.Endpoint -> {
                    rustTemplate("#{Endpoint}::builder().url(${value.url.dq()})", *codegenScope)
                    value.headers.forEach { (headerName, values) ->
                        values.forEach { headerValue ->
                            rust(".header(${headerName.dq()}, ${headerValue.dq()})")
                        }
                    }
                    // headers.forEach { (name, values) -> values.forEach { rust(".header(${name.dq()}, #W)", it) } }
                    value.properties.forEach { (name, value) ->
                        rust(
                            ".property(${name.dq()}, #W.into())",
                            generateValue(value)
                        )
                    }
                    rust(".build()")
                }
                else -> error("unexpected value")
            }
        }
    }
}
