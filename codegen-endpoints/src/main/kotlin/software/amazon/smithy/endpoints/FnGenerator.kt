/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.fn.BooleanEquals
import software.amazon.smithy.aws.reterminus.lang.fn.Fn
import software.amazon.smithy.aws.reterminus.lang.fn.GetAttr
import software.amazon.smithy.aws.reterminus.lang.fn.IsSet
import software.amazon.smithy.aws.reterminus.lang.fn.IsValidHostLabel
import software.amazon.smithy.aws.reterminus.lang.fn.Not
import software.amazon.smithy.aws.reterminus.lang.fn.ParseArn
import software.amazon.smithy.aws.reterminus.lang.fn.ParseUrl
import software.amazon.smithy.aws.reterminus.lang.fn.PartitionFn
import software.amazon.smithy.aws.reterminus.lang.fn.StringEquals
import software.amazon.smithy.aws.reterminus.visit.FnVisitor
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.join
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType

interface FnProvider {
    fun fnFor(name: String): RuntimeType?
}

fun Fn.isOptional(): Boolean {
    return this.acceptFnVisitor(object : FnVisitor<Boolean> {
        override fun visitPartition(fn: PartitionFn?): Boolean = true

        override fun visitParseArn(fn: ParseArn?): Boolean = true

        override fun visitIsValidHostLabel(fn: IsValidHostLabel?): Boolean = false

        override fun visitBoolEquals(fn: BooleanEquals?): Boolean = false

        override fun visitStringEquals(fn: StringEquals?): Boolean = false

        override fun visitIsSet(fn: IsSet?): Boolean = false

        override fun visitNot(not: Not?): Boolean = false

        override fun visitGetAttr(getAttr: GetAttr): Boolean {
            return getAttr.path().last() is GetAttr.Part.Index
        }

        override fun visitParseUrl(parseUrl: ParseUrl?): Boolean = true
    })
}

class BuiltInFnProvider : FnProvider {
    override fun fnFor(name: String): RuntimeType {
        return when (name) {
            "partition" -> endpointsInlineable("partition").member("partition")
            "isValidHostLabel" -> endpointsInlineable("generic").member("is_valid_host_label")
            "parseArn" -> endpointsInlineable("arn").member("parse_arn")
            "parseURL" -> endpointsInlineable("parse_url", CargoDependency.Http, CargoDependency.Url).member("parse_url")
            else -> TODO(name)
        }
    }
}

data class Scope(val mapping: Map<Expr, String>) {
    fun identFor(expr: Expr): String? {
        return mapping[expr]
    }

    fun withMember(name: String, expr: Expr) = Scope(mapping.plus(expr to name))

    companion object {
        fun empty() = Scope(HashMap())
    }
}

class FnGenerator(
    private val runtimeConfig: RuntimeConfig,
    private val scope: Scope,
    private val fnProvider: FnProvider = BuiltInFnProvider()
) :
    FnVisitor<Writable> {

    private fun genericFn(fn: Fn): Writable {
        val func = fnProvider.fnFor(fn.name) ?: throw Exception("no type for $fn")
        val args = fn.argv.map { ExprGenerator(Ownership.Borrowed, runtimeConfig, scope).generate(it) }
        return writable {
            rustTemplate("#{fn}(#{args:W})", "fn" to func, "args" to join(args, ","))
        }
    }

    override fun visitPartition(fn: PartitionFn): Writable = genericFn(fn)

    override fun visitParseArn(fn: ParseArn): Writable = genericFn(fn)

    override fun visitIsValidHostLabel(fn: IsValidHostLabel) = genericFn(fn)

    override fun visitParseUrl(fn: ParseUrl) = genericFn(fn)

    override fun visitBoolEquals(fn: BooleanEquals) = writable {
        val exprGenerator = ExprGenerator(Ownership.Owned, runtimeConfig, scope)
        rust("#W == #W", exprGenerator.generate(fn.left), exprGenerator.generate(fn.right))
    }

    override fun visitStringEquals(fn: StringEquals) = writable {
        val exprGenerator = ExprGenerator(Ownership.Borrowed, runtimeConfig, scope)
        rust("#W == #W", exprGenerator.generate(fn.left), exprGenerator.generate(fn.right))
    }

    override fun visitIsSet(fn: IsSet) = writable {
        val exprGenerator = ExprGenerator(Ownership.Borrowed, runtimeConfig, scope)
        rust("#W.is_some()", exprGenerator.generate(fn.target()))
    }

    override fun visitNot(not: Not) = writable {
        rust("!(#W)", ExprGenerator(Ownership.Borrowed, runtimeConfig, scope).generate(not.target()))
    }

    override fun visitGetAttr(getAttr: GetAttr): Writable {
        val target = ExprGenerator(Ownership.Borrowed, runtimeConfig, scope).generate(getAttr.target())
        val path = writable {
            getAttr.path().toList().forEach { part ->
                when (part) {
                    is GetAttr.Part.Key -> rust(".${part.key.rustName()}()")
                    is GetAttr.Part.Index -> rust(".get(${part.index}).cloned()") // we end up with Option<&&T>, we need to get to Option<&T>
                }
            }
        }
        return writable { rust("#W#W", target, path) }
    }
}
