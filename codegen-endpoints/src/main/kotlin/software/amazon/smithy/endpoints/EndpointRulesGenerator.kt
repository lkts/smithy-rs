/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.Endpoint
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.eval.Type
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Ref
import software.amazon.smithy.aws.reterminus.lang.fn.IsSet
import software.amazon.smithy.aws.reterminus.lang.rule.Condition
import software.amazon.smithy.aws.reterminus.lang.rule.Rule
import software.amazon.smithy.aws.reterminus.visit.RuleValueVisitor
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.InlineDependency
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.docs
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.orNull

fun endpointsInlineable(name: String, vararg deps: CargoDependency) =
    RuntimeType.forInlineDependency(InlineDependency.forRustFile(name, "endpoints-inlineable", *deps))

internal fun RuntimeConfig.endpoint() =
    endpointsInlineable("endpoint", CargoDependency.SmithyTypes(this)).member("Endpoint")

internal fun RuntimeConfig.endpointError() =
    endpointsInlineable("endpoint", CargoDependency.SmithyTypes(this)).member("Error")

internal fun truthy() = endpointsInlineable("truthy").member("Truthy")

class EndpointRuleGenerator(private val runtimeConfig: RuntimeConfig) {
    private val codegenScope = arrayOf(
        "Endpoint" to runtimeConfig.endpoint(),
        "EndpointError" to runtimeConfig.endpointError()
    )

    private val paramsName = "params"

    fun rulesetResolver(ruleset: EndpointRuleset): RuntimeType {
        return RuntimeType.forInlineFun("resolve_endpoint", EndpointsModule) { writer ->
            writer.rustTemplate(
                """
                pub(crate) fn resolve_endpoint($paramsName: &#{Params}) -> Result<#{Endpoint}, #{EndpointError}> {
                    use #{truthy};
                    #{ruleset:W}
                }

                """,
                *codegenScope,
                "Params" to EndpointParamsGenerator(ruleset).paramsStruct(),
                "ruleset" to generateRuleset(ruleset),
                "truthy" to truthy()
            )
        }
    }

    private fun generateRuleset(ruleset: EndpointRuleset) = writable {
        val scope = Scope.empty()
        ruleset.parameters.toList().forEach {
            rust("let ${it.memberName()} = &$paramsName.${it.memberName()};")
        }
        generateRulesList(ruleset.rules, scope)(this)
    }

    internal fun generateRule(rule: Rule, scope: Scope): Writable {
        return generateRuleInternal(rule, rule.conditions, scope)
    }

    private fun Condition.conditionalFunction(): Expr {
        return when (val fn = this.fn) {
            is IsSet -> fn.target()
            else -> fn
        }
    }

    private var nameIdx = 0
    private fun nameFor(expr: Expr): String {
        nameIdx += 1
        return when (expr) {
            is Ref -> expr.name.rustName()
            else -> "var_$nameIdx"
        }
    }

    private fun generateRuleInternal(rule: Rule, conditions: List<Condition>, scope: Scope): Writable {
        if (conditions.isEmpty()) {
            return rule.accept(RuleVisitor(scope))
        } else {
            val condition = conditions.first()
            val rest = conditions.drop(1)
            return {
                val generator = ExprGenerator(Ownership.Borrowed, runtimeConfig, scope)
                val fn = condition.conditionalFunction()
                val condName = condition.result.orNull()?.rustName() ?: nameFor(condition.conditionalFunction())

                when {
                    fn.type() is Type.Option -> rustTemplate(
                        "if let Some($condName) = #{target:W} { #{next:W} }",
                        "target" to generator.generate(fn),
                        "next" to generateRuleInternal(rule, rest, scope.withMember(condName, fn))
                    )
                    else -> {
                        check(condition.result.isEmpty)
                        rustTemplate(
                            """if (#{target:W}).truthy() { #{next:W} }""",
                            "target" to generator.generate(fn),
                            "next" to generateRuleInternal(rule, rest, scope.withMember(condName, fn))
                        )
                    }
                }
            }
        }
    }

    private fun generateRulesList(rules: List<Rule>, scope: Scope) = writable {
        rules.forEach { rule ->
            rule.documentation.orNull()?.also { docs(it, newlinePrefix = "//") }
            generateRule(rule, scope)(this)
        }
        if (rules.last().conditions.isNotEmpty()) {
            rustTemplate(
                """return Err(#{EndpointError}::msg(format!("No rules matched these parameters. This is a bug. {:?}", $paramsName)))""",
                *codegenScope
            )
        }
    }

    inner class RuleVisitor(private val scope: Scope) : RuleValueVisitor<Writable> {
        override fun visitTreeRule(rules: List<Rule>) = generateRulesList(rules, scope)

        override fun visitErrorRule(error: Expr) = writable {
            rustTemplate(
                "return Err(#{EndpointError}::msg(#{message:W}));",
                *codegenScope,
                "message" to ExprGenerator(Ownership.Owned, runtimeConfig, scope).generate(error)
            )
        }

        override fun visitEndpointRule(endpoint: Endpoint) = writable {
            rust("return Ok(#W);", generateEndpoint(endpoint, scope))
        }
    }

    internal fun generateEndpoint(endpoint: Endpoint, scope: Scope): Writable {
        val generator = ExprGenerator(Ownership.Owned, runtimeConfig, scope)
        val url = generator.generate(endpoint.url)
        val headers = endpoint.headers.mapValues { entry -> entry.value.map { generator.generate(it) } }
        val properties = endpoint.properties.mapValues { entry -> generator.generate(entry.value) }
        return writable {
            rustTemplate("#{Endpoint}::builder().url(#{url:W})", *codegenScope, "url" to url)
            headers.forEach { (name, values) -> values.forEach { rust(".header(${name.dq()}, #W)", it) } }
            properties.forEach { (name, value) -> rust(".property(${name.asString().dq()}, #W.into())", value) }
            rust(".build()")
        }
    }
}
