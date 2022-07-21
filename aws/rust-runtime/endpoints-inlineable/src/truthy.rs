/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

pub(crate) trait Truthy {
    fn truthy(&self) -> bool;
}

impl Truthy for bool {
    fn truthy(&self) -> bool {
        *self
    }
}

impl Truthy for &str {
    fn truthy(&self) -> bool {
        self.len() > 0
    }
}
