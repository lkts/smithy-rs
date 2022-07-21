/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.jetbrains.annotations.Contract
import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Literal
import software.amazon.smithy.aws.reterminus.lang.expr.Ref
import software.amazon.smithy.aws.reterminus.lang.expr.Template
import software.amazon.smithy.aws.reterminus.lang.fn.Fn
import software.amazon.smithy.aws.reterminus.visit.ExprVisitor
import software.amazon.smithy.model.SourceLocation
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

/**
 * Root expression generator. This considers [scope] for expressions that have already been resolved
 */
class ExprGenerator(
    private val ownership: Ownership,
    private val runtimeConfig: RuntimeConfig,
    private val scope: Scope
) {

    // data class Cfg(val ownership: Ownership, val wrapDocs: Boolean)
    @Contract(pure = true)
    fun generate(expr: Expr): Writable {
        val exprOrRef = scope.identFor(expr)?.let { name -> Ref(Identifier.of(name), SourceLocation.none()) } ?: expr
        return exprOrRef.accept(ExprGeneratorVisitor(ownership, runtimeConfig, scope))
    }

    /**
     * Inner generator based on ExprVisitor
     */
    private class ExprGeneratorVisitor(
        private val ownership: Ownership,
        private val runtimeConfig: RuntimeConfig,
        private val scope: Scope
    ) :
        ExprVisitor<Writable> {
        private val document = CargoDependency.SmithyTypes(runtimeConfig).asType().member("Document")
        private val codegenScope = arrayOf("Document" to document, "HashMap" to RustType.HashMap.RuntimeType)

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
                                ExprGenerator(ownership, runtimeConfig, scope).generate(expr)
                            }
                        )
                        parts.forEach { part -> part(writer) }
                    }

                    override fun visitObject(members: MutableMap<Identifier, Literal>) {
                        rustBlock("") {
                            rustTemplate(
                                "let mut out = #{HashMap}::<String, #{Document}>::new();",
                                *codegenScope
                            )
                            members.forEach { (identifier, literal) ->
                                rust(
                                    "out.insert(${identifier.toString().dq()}.to_string(), #W.into());",
                                    // When writing into the hashmap, it always needs to be an owned type
                                    ExprGenerator(Ownership.Owned, runtimeConfig, scope).generate(literal)
                                )
                            }
                            rustTemplate("out")
                        }
                    }

                    override fun visitTuple(members: MutableList<Literal>) {
                        rustTemplate(
                            "vec![#{inner:W}]", *codegenScope,
                            "inner" to writable {
                                members.forEach { literal ->
                                    rustTemplate(
                                        "#{Document}::from(#{literal:W}),",
                                        *codegenScope,
                                        "literal" to ExprGenerator(
                                            Ownership.Owned,
                                            runtimeConfig,
                                            scope
                                        ).generate(literal)
                                    )
                                }
                            }
                        )
                    }
                })
            }
        }

        override fun visitRef(ref: Ref) = writable {
            rust(ref.name.rustName())
            if (ownership == Ownership.Owned) {
                rust(".to_owned()")
            }
        }

        override fun visitFn(fn: Fn): Writable {
            return fn.acceptFnVisitor(FnGenerator(runtimeConfig, scope))
        }
    }
}
