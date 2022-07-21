/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest

internal class ExprGeneratorTest {
    @Test
    fun generateExprs() {
        val boolEq = Expr.of(true).eq(true)
        val strEq = Expr.of("helloworld").eq("goodbyeworld")
        TestWorkspace.testProject().unitTest {
            val generator = ExprGenerator(Ownership.Borrowed, TestRuntimeConfig, Scope.empty())
            rust("assert_eq!(true, #W);", generator.generate(boolEq))
            rust("assert_eq!(false, #W);", generator.generate(strEq))
        }.compileAndTest()
    }
}
