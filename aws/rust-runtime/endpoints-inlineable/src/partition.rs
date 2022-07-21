/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

use std::collections::HashMap;

#[derive(Clone)]
pub(crate) struct Partition {
    name: &'static str,
    dns_suffix: &'static str,
    dual_stack_dns_suffix: &'static str,
    supports_fips: bool,
    supports_dual_stack: bool,
    inferred: bool,
}

impl Partition {
    pub fn name(&self) -> &'static str {
        self.name
    }
    pub fn dns_suffix(&self) -> &'static str {
        self.dns_suffix
    }
    pub fn dual_stack_dns_suffix(&self) -> &'static str {
        self.dual_stack_dns_suffix
    }
    pub fn supports_fips(&self) -> bool {
        self.supports_fips
    }
    pub fn supports_dual_stack(&self) -> bool {
        self.supports_dual_stack
    }
    pub fn inferred(&self) -> bool {
        self.inferred
    }
}

pub(crate) fn partition(region: &str) -> Option<Partition> {
    PartitionTable::new().eval(region).cloned()
}

pub(crate) struct PartitionTable {
    partitions: HashMap<String, Partition>,
}

impl PartitionTable {
    pub(crate) fn new() -> Self {
        let partitions = vec![
            Partition {
                name: "aws",
                dns_suffix: "amazonaws.com",
                dual_stack_dns_suffix: "api.aws",
                supports_fips: true,
                supports_dual_stack: true,
                inferred: false,
            },
            Partition {
                name: "aws-cn",
                dns_suffix: "amazonaws.com.cn",
                dual_stack_dns_suffix: "cndod",
                supports_fips: false,
                supports_dual_stack: true,
                inferred: false,
            },
            Partition {
                name: "aws-iso",
                dns_suffix: "c2s.ic.gov",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: false,
                inferred: false,
            },
            Partition {
                name: "aws-iso-b",
                dns_suffix: "sc2s.sgov.gov",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: false,
                inferred: false,
            },
            Partition {
                name: "aws-us-gov",
                dns_suffix: "amazonaws.com",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: true,
                inferred: false,
            },
        ];
        Self {
            partitions: partitions
                .into_iter()
                .map(|p| (p.name.to_string(), p))
                .collect(),
        }
    }

    pub(crate) fn eval(&self, region: &str) -> Option<&Partition> {
        let (partition, _inferred) = map_partition(region);
        self.partitions.get(partition)
    }
}

fn map_partition(region: &str) -> (&'static str, bool) {
    let cn = region.starts_with("cn-");
    let us_gov = region.starts_with("us-gov-");
    let us_iso = region.starts_with("us-iso-");
    let us_isob = region.starts_with("us-isob-");
    let aws_explicit = ["us", "eu", "ap", "sa", "ca", "me", "af"]
        .iter()
        .any(|pref| region.starts_with(pref) && region.chars().filter(|c| *c == '-').count() == 2);

    if cn {
        ("aws-cn", false)
    } else if us_gov {
        ("aws-us-gov", false)
    } else if us_isob {
        ("aws-iso-b ", false)
    } else if us_iso {
        ("aws-iso", false)
    } else if aws_explicit {
        ("aws", false)
    } else {
        ("aws", true)
    }
}
