/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

#[derive(Debug, Eq, PartialEq)]
pub(crate) struct Arn<'a> {
    partition: &'a str,
    service: &'a str,
    region: &'a str,
    account_id: &'a str,
    resource_id: Vec<&'a str>,
}

impl<'a> Arn<'a> {
    pub fn partition(&self) -> &'a str {
        self.partition
    }
    pub fn service(&self) -> &'a str {
        self.service
    }
    pub fn region(&self) -> &'a str {
        self.region
    }
    pub fn account_id(&self) -> &'a str {
        self.account_id
    }
    pub fn resource_id(&self) -> &Vec<&'a str> {
        &self.resource_id
    }
}

impl<'a> Arn<'a> {
    pub(crate) fn parse(arn: &'a str) -> Option<Self> {
        let mut split = arn.splitn(6, ':');
        let _arn = split.next()?;
        let partition = split.next()?;
        let service = split.next()?;
        let region = split.next()?;
        let account_id = split.next()?;
        let resource_id = split.next()?.split(':').collect::<Vec<_>>();
        Some(Self {
            partition,
            service,
            region,
            account_id,
            resource_id,
        })
    }
}

pub(crate) fn parse_arn(input: &str) -> Option<Arn> {
    Arn::parse(input)
}

#[cfg(test)]
mod test {
    use super::Arn;

    #[test]
    fn arn_parser() {
        let arn = "arn:aws:s3:us-east-2:012345678:outpost:op-1234";
        let parsed = Arn::parse(arn).expect("valid ARN");
        assert_eq!(
            parsed,
            Arn {
                partition: "aws",
                service: "s3",
                region: "us-east-2",
                account_id: "012345678",
                resource_id: vec!["outpost", "op-1234"]
            }
        );
    }
}
