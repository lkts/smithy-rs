/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Literal
import software.amazon.smithy.aws.reterminus.lang.expr.Ref
import software.amazon.smithy.model.SourceLocation
import software.amazon.smithy.model.node.ArrayNode
import software.amazon.smithy.model.node.BooleanNode
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest

internal class ExprGeneratorVisitorTest {
    @Test
    fun generateLiterals() {
        val project = TestWorkspace.testProject()
        val gen = ExprGenerator(
            Ownership.Borrowed,
            TestRuntimeConfig,
            Scope.empty().withMember("extra", Ref(Identifier.of("ref"), SourceLocation.none()))
        )
        project.unitTest {
            rust("""let extra = "helloworld";""")
            rust("assert_eq!(true, #W);", gen.generate(Expr.of(true)))
            rust("assert_eq!(false, #W);", gen.generate(Expr.of(false)))
            rust("""assert_eq!("blah", #W);""", gen.generate(Expr.of("blah")))
            rust("""assert_eq!("helloworld: rust", #W);""", gen.generate(Expr.of("{ref}: rust")))
            rustTemplate(
                """
                let mut expected = std::collections::HashMap::new();
                expected.insert("a".to_string(), #{Document}::Bool(true));
                expected.insert("b".to_string(), #{Document}::String("hello".to_string()));
                expected.insert("c".to_string(), #{Document}::Array(vec![true.into()]));
                assert_eq!(expected, #{actual:W});
                """,
                "Document" to CargoDependency.SmithyTypes(TestRuntimeConfig).asType().member("Document"),
                "actual" to gen.generate(
                    Literal.fromNode(
                        Node.objectNode().withMember("a", true).withMember("b", "hello")
                            .withMember("c", ArrayNode.arrayNode(BooleanNode.from(true))),
                    )
                )
            )
        }
        project.compileAndTest()
    }
}
