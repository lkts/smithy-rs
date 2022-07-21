/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

pub(crate) fn is_valid_host_label(label: &str, allow_dots: bool) -> bool {
    if allow_dots {
        for part in label.split('.') {
            if !is_valid_host_label(part, false) {
                return false;
            }
        }
        true
    } else {
        if label.len() < 1 || label.len() > 63 {
            return false;
        }
        if !label.starts_with(|c: char| c.is_alphabetic()) {
            return false;
        }
        if !label.chars().all(|c| c.is_alphanumeric() || c == '-') {
            return false;
        }
        true
    }
}
