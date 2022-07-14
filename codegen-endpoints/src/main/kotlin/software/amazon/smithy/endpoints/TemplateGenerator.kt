/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.visit.TemplateVisitor
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.util.dq

/**
 * Template Generator
 *
 * Templates can be in one of 3 possible formats:
 * 1. Single static string: `https://staticurl.com`. In this case, we return a string literal.
 * 2. Single dynamic string: `{Region}`. In this case we delegate directly to the underlying expression.
 * 3. Compound string: `https://{Region}.example.com`. In this case, we use a string builder:
 * ```rust
 * {
 *   let mut out = String::new();
 *   out.push_str("https://");
 *   out.push_str(region);
 *   out.push_str(".example.com);
 *   out
 * }
 * ```
 */
class TemplateGenerator(
    private val ownership: ExprGenerator.Ownership,
    private val exprGenerator: (Expr, ExprGenerator.Ownership) -> Writable
) : TemplateVisitor<Writable> {
    override fun visitStaticTemplate(value: String) = writable {
        // In the case of a static template, return the literal string, eg. `"foo"`.
        rust(value.dq())
        if (ownership == ExprGenerator.Ownership.Owned) {
            rust(".to_string()")
        }
    }

    override fun visitSingleDynamicTemplate(expr: Expr): Writable {
        return exprGenerator(expr, ownership)
    }

    override fun visitStaticElement(str: String) = writable {
        rust("out.push_str(${str.dq()});")
    }

    override fun visitDynamicElement(expr: Expr) = writable {
        // we don't need to own the argument to push_str
        rust("out.push_str(#W);", exprGenerator(expr, ExprGenerator.Ownership.Borrowed))
    }

    override fun startMultipartTemplate() = writable {
        if (ownership == ExprGenerator.Ownership.Borrowed) {
            rust("&")
        }
        rust("{ let mut out = String::new();")
    }

    override fun finishMultipartTemplate() = writable {
        rust(" out }")
    }
}
