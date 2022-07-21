/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.endpoints

import software.amazon.smithy.aws.reterminus.EndpointRuleset
import software.amazon.smithy.aws.reterminus.eval.Value
import software.amazon.smithy.aws.reterminus.lang.Identifier
import software.amazon.smithy.aws.reterminus.lang.parameters.Parameter
import software.amazon.smithy.aws.reterminus.lang.parameters.ParameterType
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.rustlang.RustReservedWords
import software.amazon.smithy.rust.codegen.rustlang.RustType
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.asDeref
import software.amazon.smithy.rust.codegen.rustlang.docs
import software.amazon.smithy.rust.codegen.rustlang.isCopy
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.RuntimeType.Companion.Clone
import software.amazon.smithy.rust.codegen.smithy.RuntimeType.Companion.Debug
import software.amazon.smithy.rust.codegen.smithy.RuntimeType.Companion.Default
import software.amazon.smithy.rust.codegen.smithy.RuntimeType.Companion.PartialEq
import software.amazon.smithy.rust.codegen.smithy.isOptional
import software.amazon.smithy.rust.codegen.smithy.letIf
import software.amazon.smithy.rust.codegen.smithy.makeOptional
import software.amazon.smithy.rust.codegen.smithy.mapRustType
import software.amazon.smithy.rust.codegen.smithy.rustType
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.orNull
import software.amazon.smithy.rust.codegen.util.toSnakeCase

/**
 * Utility function to convert an [Identifier] into a valid Rust identifier (snake case)
 */
fun Identifier.rustName(): String {
    return RustReservedWords.escapeIfNeeded(this.toString().toSnakeCase())
}

/**
 * Returns the memberName() for a given [Parameter]
 */
fun Parameter.memberName(): String {
    return name.rustName()
}

/**
 * Returns the symbol for a given parameter. This enables [RustWriter] to generate the correct [RustType].
 */
fun Parameter.symbol(): Symbol {
    val rustType = when (this.type) {
        ParameterType.STRING -> RustType.String
        ParameterType.BOOLEAN -> RustType.Bool
    }
    // Parameter return types are always optional
    return Symbol.builder().rustType(rustType).build().letIf(!this.isRequired) { it.makeOptional() }
}

val EndpointsModule = RustModule.public("endpoint_resolver", "Endpoint resolution functionality")

/** Endpoint Parameters generator. Intended to be generated into the `endpoint` module */
class EndpointParamsGenerator(private val endpointRules: EndpointRuleset) {

    fun paramsStruct(): RuntimeType = RuntimeType.forInlineFun("Params", EndpointsModule) { writer ->
        generateEndpointsStruct(writer)
    }

    fun endpointsBuilder(): RuntimeType = RuntimeType.forInlineFun("Builder", EndpointsModule) { writer ->
        generateEndpointParamsBuilder(writer)
    }

    fun paramsError(): RuntimeType = RuntimeType.forInlineFun("Error", EndpointsModule) { writer ->
        writer.rust(
            """
            /// An error that occurred during endpoint resolution
            ##[derive(Debug)]
            ##[non_exhaustive ]
            pub enum Error {
                /// A required field was missing
                MissingRequiredField {
                    /// Name of the missing field
                    field: std::borrow::Cow<'static, str>
                },
                /// A valid endpoint could not be resolved
                EndpointResolutionError {
                    /// The error message
                    message: std::borrow::Cow<'static, str>
                }
            }
            impl Error {
                fn missing(field: &'static str) -> Self {
                    Self::MissingRequiredField { field: field.into() }
                }
            }

            impl std::fmt::Display for Error {
                fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                    match self {
                        Error::MissingRequiredField { field } => write!(f, "A required field was missing: `{}`", field),
                        Error::EndpointResolutionError { message } => write!(f, "A valid endpoint could not be resolved: {}.", message)
                    }
                }
            }

            impl std::error::Error for Error { }
            """
        )
    }

    /**
     * Generates an endpoints struct based on the provided endpoint rules. The struct fields are `pub(crate)`
     * with optionality as indicated by the required status of the parameter.
     */
    internal fun generateEndpointsStruct(writer: RustWriter) {
        // Ensure that fields can be added in the future
        Attribute.NonExhaustive.render(writer)
        // Automatically implement standard Rust functionality
        Attribute.Derives(setOf(Debug, PartialEq, Clone)).render(writer)
        // Generate the struct block:
        /*
            pub struct Params {
                ... members: pub(crate) field
            }
         */
        writer.docs("Configuration parameters for resolving the correct endpoint")
        writer.rustBlock("pub struct Params") {
            endpointRules.parameters.toList().forEach { parameter ->
                // Render documentation for each parameter
                parameter.documentation.orNull()?.also { docs(it) }
                rust("pub(crate) ${parameter.memberName()}: #T,", parameter.symbol())
            }
        }

        // Generate the impl block for the struct
        writer.rustBlock("impl Params") {
            rustTemplate(
                """
                /// Create a builder for [`Params`]
                pub fn builder() -> #{Builder} {
                    #{Builder}::default()
                }
                """,
                "Builder" to endpointsBuilder()
            )
            endpointRules.parameters.toList().forEach { parameter ->
                val name = parameter.memberName()
                val type = parameter.symbol()

                parameter.documentation.orNull()?.also { docs(it) }
                rustTemplate(
                    """
                    pub fn ${parameter.memberName()}(&self) -> #{paramType} {
                        #{param:W}
                    }

                    """,
                    "paramType" to type.makeOptional().mapRustType { t -> t.asDeref() },
                    "param" to writable {
                        when {
                            type.isOptional() && type.rustType().isCopy() -> rust("self.$name")
                            type.isOptional() -> rust("self.$name.as_deref()")
                            type.rustType().isCopy() -> rust("Some(self.$name)")
                            else -> rust("Some(&self.$name)")
                        }
                    }
                )
            }
        }
    }

    private fun value(value: Value): String {
        return when (value) {
            is Value.Str -> value.value().dq() + ".to_string()"
            is Value.Bool -> value.expectBool().toString()
            else -> TODO("$value")
        }
    }

    private fun generateEndpointParamsBuilder(rustWriter: RustWriter) {
        rustWriter.docs("Builder for [`Params`]")
        Attribute.Derives(setOf(Debug, Default, PartialEq, Clone)).render(rustWriter)
        rustWriter.rustBlock("pub struct Builder") {
            endpointRules.parameters.toList().forEach { parameter ->
                val name = parameter.memberName()
                val type = parameter.symbol().makeOptional()
                rust("$name: #T,", type)
            }
        }

        rustWriter.rustBlock("impl Builder") {
            docs("Consume this builder, creating [`Params`].")
            rustBlockTemplate(
                "pub fn build(self) -> Result<#{Params}, #{ParamsError}>",
                "Params" to paramsStruct(),
                "ParamsError" to paramsError()
            ) {

                val params = writable {
                    rustBlockTemplate("#{Params}", "Params" to paramsStruct()) {
                        endpointRules.parameters.toList().forEach { parameter ->
                            rust("${parameter.memberName()}: self.${parameter.memberName()}")
                            parameter.default.orNull()?.also { default -> rust(".or(Some(${value(default)}))") }
                            if (parameter.isRequired) {
                                rustTemplate(
                                    ".ok_or_else(||#{Error}::missing(${parameter.memberName().dq()}))?",
                                    "Error" to paramsError()
                                )
                            }
                            rust(",")
                        }
                    }
                }
                rust("Ok(#W)", params)
            }
            endpointRules.parameters.toList().forEach { parameter ->
                val name = parameter.memberName()
                val type = parameter.symbol().mapRustType { t -> t.stripOuter<RustType.Option>() }
                rustTemplate(
                    """
                    /// Sets the value for $name
                    #{extraDocs:W}
                    pub fn $name(mut self, value: #{type}) -> Self {
                        self.$name = Some(value);
                        self
                    }
                    """,
                    "type" to type,
                    "extraDocs" to writable {
                        if (parameter.default.isPresent || parameter.documentation.isPresent) {
                            rust("///")
                        }
                        parameter.default.orNull()?.also {
                            docs("When unset, this parameter has a default value of `$it`.")
                        }
                        parameter.documentation.orNull()?.also { docs(it) }
                    }
                )
            }
        }
    }
}
