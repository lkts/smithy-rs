/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_types::Document;
use std::collections::HashMap;

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Error {
    message: String,
}

impl Error {
    pub fn msg(s: impl Into<String>) -> Self {
        Self { message: s.into() }
    }
}

#[derive(Debug, PartialEq)]
pub(crate) struct Endpoint {
    url: String,
    headers: HashMap<String, Vec<String>>,
    properties: HashMap<String, Document>,
}

impl Endpoint {
    pub(crate) fn url(&self) -> &str {
        &self.url
    }

    pub(crate) fn headers(&self) -> &HashMap<String, Vec<String>> {
        &self.headers
    }

    pub(crate) fn properties(&self) -> &HashMap<String, Document> {
        &self.properties
    }

    pub(crate) fn builder() -> Builder {
        Builder::new()
    }
}

// TODO: impl TryInto<aws::Endpoint> for Endpoint

pub(crate) struct Builder {
    endpoint: Endpoint,
}

impl Builder {
    pub(crate) fn new() -> Self {
        Self {
            endpoint: Endpoint {
                url: String::new(),
                headers: HashMap::new(),
                properties: HashMap::new(),
            },
        }
    }

    pub(crate) fn url(mut self, url: impl Into<String>) -> Self {
        self.endpoint.url = url.into();
        self
    }

    pub(crate) fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.endpoint
            .headers
            .entry(name.into())
            .or_default()
            .push(value.into());
        self
    }

    pub(crate) fn property(mut self, key: impl Into<String>, value: Document) -> Self {
        self.endpoint.properties.insert(key.into(), value);
        self
    }

    pub(crate) fn build(self) -> Endpoint {
        self.endpoint
    }
}
