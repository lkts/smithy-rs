/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

use http::Uri;
use url::{Host, Url as ParsedUrl};

pub(crate) struct Url {
    uri: Uri,
    url: ParsedUrl,
}

impl Url {
    pub(crate) fn is_ip(&self) -> bool {
        match self.url.host() {
            Some(Host::Ipv4(_) | Host::Ipv6(_)) => true,
            _ => false,
        }
    }
    pub(crate) fn scheme(&self) -> &str {
        self.url.scheme()
    }

    pub(crate) fn authority(&self) -> &str {
        self.uri.authority().unwrap().as_str()
    }

    pub(crate) fn normalized_path(&self) -> &str {
        match self.uri.path() {
            path if !path.is_empty() => path,
            _ => "/",
        }
    }

    pub(crate) fn path(&self) -> &str {
        self.uri.path()
    }
}

pub(crate) fn parse_url(url: &str) -> Option<Url> {
    let uri: Uri = url.parse().ok()?;
    let url: ParsedUrl = url.parse().ok()?;
    if uri.query() != None {
        return None;
    }
    if !["http", "https"].contains(&url.scheme()) {
        return None;
    }
    Some(Url { url, uri })
}

#[cfg(test)]
mod test {
    #[test]
    fn parse_simple_url() {}
}
