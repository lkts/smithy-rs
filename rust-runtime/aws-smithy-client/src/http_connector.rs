/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Default connectors based on what TLS features are active. Also contains HTTP-related abstractions
//! that enable passing HTTP connectors around.

use crate::erase::DynConnector;
use aws_smithy_async::rt::sleep::AsyncSleep;
use aws_smithy_types::timeout;
use http::version::Version as HttpVersion;
use std::{borrow::Cow, fmt::Debug, sync::Arc};

type BoxError = Box<dyn std::error::Error + Send + Sync>;

/// Type alias for a Connector factory function.
pub type MakeConnectorFn = dyn Fn(&MakeConnectorSettings, Option<Arc<dyn AsyncSleep>>) -> Result<DynConnector, BoxError>
    + Send
    + Sync;

/// Enum for describing the two "kinds" of HTTP Connectors in smithy-rs.
#[derive(Clone)]
pub enum HttpConnector {
    /// A `DynConnector` to be used for all requests.
    Prebuilt(Option<DynConnector>),
    /// A factory function that will be used to create new `DynConnector`s whenever one is needed.
    ConnectorFn(Arc<MakeConnectorFn>),
}

impl Debug for HttpConnector {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Prebuilt(Some(connector)) => {
                write!(f, "Prebuilt({:?})", connector)
            }
            Self::Prebuilt(None) => {
                write!(f, "Prebuilt(None)")
            }
            Self::ConnectorFn(_) => {
                write!(f, "ConnectorFn(<function pointer>)")
            }
        }
    }
}

impl HttpConnector {
    /// If `HttpConnector` is `Prebuilt`, return a clone of that connector.
    /// If `HttpConnector` is `ConnectorFn`, generate a new connector from settings and return it.
    pub fn load(
        &self,
        settings: &MakeConnectorSettings,
        sleep: Option<Arc<dyn AsyncSleep>>,
    ) -> Result<DynConnector, Box<dyn std::error::Error + Send + Sync>> {
        match self {
            HttpConnector::Prebuilt(Some(conn)) => Ok(conn.clone()),
            HttpConnector::Prebuilt(None) => todo!("What's the use case for this?"),
            HttpConnector::ConnectorFn(func) => func(settings, sleep),
        }
    }

    /// Attempt to create an [HttpConnector] from defaults. This will return an
    /// [`Err(HttpConnectorError::NoAvailableDefault)`](HttpConnectorError) if default features
    /// are disabled.
    pub fn try_default() -> Result<Self, HttpConnectorError> {
        if cfg!(feature = "rustls") {
            todo!("How do I actually create this?");
            // Ok(HttpConnector::ConnectorFn(Arc::new(
            //     |settings: &MakeConnectorSettings, sleep_impl: Option<Arc<dyn AsyncSleep>>| {
            //         Ok(DynConnector::new(crate::conns::https()))
            //     },
            // )))
        } else {
            Err(HttpConnectorError::NoAvailableDefault)
        }
    }
}

/// Settings used to create new HTTP connectors.
#[non_exhaustive]
#[derive(Default, Debug, Clone, Hash, PartialEq, Eq)]
pub struct MakeConnectorSettings {
    /// Timeout configuration used when making HTTP connections
    pub http_timeout_config: timeout::Http,
    /// Timeout configuration used when creating TCP connections
    pub tcp_timeout_config: timeout::Tcp,
}

impl MakeConnectorSettings {
    /// Set the HTTP timeouts to be used when making HTTP connections
    pub fn with_http_timeout_config(mut self, http_timeout_config: timeout::Http) -> Self {
        self.http_timeout_config = http_timeout_config;
        self
    }

    /// Set the TCP timeouts to be used when creating TCP connections
    pub fn with_tcp_timeout_config(mut self, tcp_timeout_config: timeout::Tcp) -> Self {
        self.tcp_timeout_config = tcp_timeout_config;
        self
    }
}

/// Errors related to the creation and use of [HttpConnector]s
#[non_exhaustive]
#[derive(Debug)]
pub enum HttpConnectorError {
    /// Tried to create a new [HttpConnector] from default but couldn't because default features were disabled
    NoAvailableDefault,
    /// Expected an [HttpConnector] to be set but none was set
    NoConnectorDefined,
    /// Expected at least one [http::Version] to be set but none was set
    NoHttpVersionsSpecified,
}

impl std::fmt::Display for HttpConnectorError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        use HttpConnectorError::*;
        match self {
            NoAvailableDefault => {
                // TODO Update this error message with a link to an example demonstrating how to fix it
                write!(
                    f,
                    "When default features are disabled, an HttpConnector must be set manually."
                )
            }
            NoConnectorDefined => {
                write!(
                    f,
                    // TODO in what cases does this error actually appear?
                    "No connector was defined"
                )
            }
            // TODO should this really be an error?
            NoHttpVersionsSpecified => {
                write!(f, "Couldn't get or create a client because no HTTP versions were specified as valid.")
            }
        }
    }
}

impl std::error::Error for HttpConnectorError {}

/// A hashable struct used to key into a hashmap storing different HTTP Clients
#[derive(Debug, Hash, Eq, PartialEq)]
pub struct ConnectorKey<'a> {
    /// The HTTP-related settings that were used to create the client that this key points to
    pub make_connector_settings: Cow<'a, MakeConnectorSettings>,
    /// The desired HTTP version that was used to create the client that this key points to
    pub http_version: HttpVersion,
}

impl<'a> ConnectorKey<'a> {
    /// Given a `ConnectorKey` that might contain a non-`'static` reference, return a `ConnectorKey`
    /// containing an owned value with a `'static` lifetime.
    pub fn into_owned(self) -> ConnectorKey<'static> {
        ConnectorKey {
            make_connector_settings: Cow::Owned(self.make_connector_settings.into_owned()),
            http_version: self.http_version,
        }
    }
}
