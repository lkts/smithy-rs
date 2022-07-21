/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import org.intellij.lang.annotations.Language
import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.testutil.TestDiscovery
import software.amazon.smithy.model.node.Node
import java.util.stream.Stream

@Language("JSON")
fun String.toRuleset(): EndpointRuleset {
    return EndpointRuleset.fromNode(Node.parse(this))
}

fun testSuites(): Stream<TestDiscovery.RulesTestSuite> = TestDiscovery().testSuites()

val TestRuleset: EndpointRuleset = """{
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
