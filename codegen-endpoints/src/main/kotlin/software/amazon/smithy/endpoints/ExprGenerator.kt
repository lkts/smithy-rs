/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.expr.Literal
import software.amazon.smithy.aws.reterminus.lang.expr.Ref
import software.amazon.smithy.aws.reterminus.lang.expr.Template
import software.amazon.smithy.aws.reterminus.lang.fn.Fn
import software.amazon.smithy.aws.reterminus.visit.ExprVisitor
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustType
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.util.dq
import java.util.stream.Stream

class ExprGenerator(private val ownership: Ownership, private val runtimeConfig: RuntimeConfig) :
    ExprVisitor<Writable> {
    private val document = CargoDependency.SmithyTypes(runtimeConfig).asType().member("Document")
    override fun visitLiteral(literal: Literal): Writable {
        return writable {
            val writer = this
            literal.accept(object : Literal.Vistor<Unit> {
                override fun visitBool(b: Boolean) {
                    rust(b.toString())
                }

                override fun visitStr(value: Template) {
                    val parts: Stream<Writable> = value.accept(
                        TemplateGenerator(ownership) { expr, ownership ->
                            expr.accept(ExprGenerator(ownership, runtimeConfig))
                        }
                    )
                    parts.forEach { part -> part(writer) }
                }

                override fun visitObject(members: MutableMap<Identifier, Literal>) {
                    rustBlock("") {
                        rustTemplate(
                            "let mut out = #{HashMap}::<String, #{Document}>::new();",
                            "HashMap" to RustType.HashMap.RuntimeType,
                            "Document" to document
                        )
                        members.forEach { (identifier, literal) ->
                            rust(
                                "out.insert(${identifier.toString().dq()}.to_string(), #W.into());",
                                // When writing into the hashmap, it always needs to be an owned type
                                literal.accept(ExprGenerator(Ownership.Owned, runtimeConfig))
                            )
                        }
                        rustTemplate("out")
                    }
                }

                override fun visitTuple(members: MutableList<Literal>) {
                    rustTemplate(
                        "#{Document}::Array(vec![#{inner:W}])", "Document" to document,
                        "inner" to writable {
                            members.forEach { literal ->
                                literal.accept(
                                    ExprGenerator(
                                        Ownership.Owned,
                                        runtimeConfig
                                    )
                                )(this)
                                rust(".into()")
                                rust(",")
                            }
                        }
                    )
                }
            })
        }
    }

    override fun visitRef(ref: Ref): Writable {
        TODO("Not yet implemented")
    }

    override fun visitFn(fn: Fn): Writable {
        TODO("Not yet implemented")
    }
}
