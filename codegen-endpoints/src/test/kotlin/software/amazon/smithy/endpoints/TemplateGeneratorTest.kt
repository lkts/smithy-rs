/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Template
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest
import software.amazon.smithy.rust.codegen.util.dq

internal class TemplateGeneratorTest {
    private fun assertTemplateEquals(template: String, result: String) {
        val literalTemplate = Template.fromString(template)
        // For testing,
        val exprFn = { expr: Expr, ownership: Ownership ->
            writable {
                rust(
                    (expr.toString().uppercase() + ownership).dq()
                )
                if (ownership == Ownership.Owned) {
                    rust(".to_string()")
                }
            }
        }
        val borrowedGenerator = TemplateGenerator(Ownership.Borrowed, exprFn)
        val ownedGenerator = TemplateGenerator(Ownership.Owned, exprFn)
        val project = TestWorkspace.testProject()
        project.unitTest {
            rust(
                "assert_eq!(${result.dq()}, #W);",
                writable {
                    literalTemplate.accept(borrowedGenerator).forEach { part -> part(this) }
                }
            )
            rust(
                "let _: String = #W;",
                writable { literalTemplate.accept(ownedGenerator).forEach { part -> part(this) } }
            )
        }
        project.compileAndTest()
    }

    @Test
    fun testLiteralTemplate() {
        assertTemplateEquals("https://example.com", "https://example.com")
    }

    @Test
    fun testDynamicTemplate() {
        assertTemplateEquals("https://{Region}.example.com", "https://REGIONBorrowed.example.com")
    }

    @Test
    fun testSingleDynamicTemplate() {
        assertTemplateEquals("{Region}", "REGIONBorrowed")
    }
}
