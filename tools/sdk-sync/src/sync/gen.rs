/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::fs::Fs;
use crate::git::{CommitHash, Git, GitCLI};
use anyhow::{Context, Result};
use smithy_rs_tool_common::here;
use smithy_rs_tool_common::shell::handle_failure;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use tracing::{info, instrument};

pub struct GeneratedSdk {
    path: PathBuf,
    // Keep a reference to the temp directory so that it doesn't get cleaned up
    // until the generated SDK is no longer referenced anywhere.
    _temp_dir: Option<Arc<tempfile::TempDir>>,
}

impl GeneratedSdk {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self {
            path: path.into(),
            _temp_dir: None,
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

/// Generates a SDK.
#[cfg_attr(test, mockall::automock)]
pub trait SdkGenerator {
    /// Generates the full SDK and returns a path to the generated SDK artifacts.
    fn generate_sdk(&self) -> Result<GeneratedSdk>;
}

/// SDK generator that creates a temporary directory and clones the given `smithy-rs` into it
/// so that generation can safely be done in parallel for other commits.
pub struct DefaultSdkGenerator {
    previous_versions_manifest: PathBuf,
    aws_doc_sdk_examples_revision: CommitHash,
    examples_path: PathBuf,
    fs: Arc<dyn Fs>,
    smithy_rs: Box<dyn Git>,
    smithy_parallelism: usize,
    temp_dir: Arc<tempfile::TempDir>,
}

impl DefaultSdkGenerator {
    #[instrument(skip(fs))]
    pub fn new(
        previous_versions_manifest: &Path,
        aws_doc_sdk_examples_revision: &CommitHash,
        examples_path: &Path,
        fs: Arc<dyn Fs>,
        reset_to_commit: Option<CommitHash>,
        original_smithy_rs_path: &Path,
        smithy_parallelism: usize,
    ) -> Result<Self> {
        let temp_dir = tempfile::tempdir().context(here!("create temp dir"))?;
        GitCLI::new(original_smithy_rs_path)
            .context(here!())?
            .clone_to(temp_dir.path())
            .context(here!())?;

        let smithy_rs = GitCLI::new(&temp_dir.path().join("smithy-rs")).context(here!())?;
        if let Some(smithy_rs_commit) = reset_to_commit {
            smithy_rs
                .hard_reset(smithy_rs_commit.as_ref())
                .with_context(|| format!("failed to reset to {} in smithy-rs", smithy_rs_commit))?;
        }

        Ok(Self {
            previous_versions_manifest: previous_versions_manifest.into(),
            aws_doc_sdk_examples_revision: aws_doc_sdk_examples_revision.clone(),
            examples_path: examples_path.into(),
            fs,
            smithy_rs: Box::new(smithy_rs) as Box<dyn Git>,
            smithy_parallelism,
            temp_dir: Arc::new(temp_dir),
        })
    }

    /// Copies examples into smithy-rs.
    #[instrument(skip(self))]
    fn copy_examples(&self) -> Result<()> {
        info!("Cleaning examples...");
        self.fs
            .remove_dir_all_idempotent(&self.smithy_rs.path().join("aws/sdk/examples"))
            .context(here!())?;

        let from = &self.examples_path;
        let to = self.smithy_rs.path().join("aws/sdk/examples");
        info!("Copying examples from {:?} to {:?}...", from, to);
        self.fs.recursive_copy(from, &to).context(here!())?;

        // Remove files that may come in from aws-doc-sdk-examples that are not needed
        self.fs
            .remove_dir_all_idempotent(&to.join(".cargo"))
            .context(here!())?;
        self.fs
            .remove_file_idempotent(&to.join("Cargo.toml"))
            .context(here!())?;
        Ok(())
    }

    /// Runs `aws:sdk:assemble` target with property `aws.fullsdk=true` set
    #[instrument(skip(self))]
    fn aws_sdk_assemble(&self) -> Result<()> {
        info!("Generating the SDK...");

        let mut command = Command::new("./gradlew");
        command.arg("--no-daemon"); // Don't let Gradle continue running after the build
        command.arg("--no-parallel"); // Disable Gradle parallelism
        command.arg("--max-workers=1"); // Cap the Gradle workers at 1
        command.arg("--info"); // Increase logging verbosity for failure debugging

        // Customize the Gradle daemon JVM args (these are required even with `--no-daemon`
        // since Gradle still forks out a daemon process that gets terminated at the end)
        command.arg(format!(
            "-Dorg.gradle.jvmargs={}",
            [
                // Retain default Gradle JVM args
                "-Xmx512m",
                "-XX:MaxMetaspaceSize=256m",
                // Disable incremental compilation and caching since we're compiling exactly once per commit
                "-Dkotlin.incremental=false",
                "-Dkotlin.caching.enabled=false",
                // Run the compiler in the gradle daemon process to avoid more forking thrash
                "-Dkotlin.compiler.execution.strategy=in-process"
            ]
            .join(" ")
        ));

        // Disable Smithy's codegen parallelism in favor of sdk-sync parallelism
        command.arg(format!(
            "-Djava.util.concurrent.ForkJoinPool.common.parallelism={}",
            self.smithy_parallelism
        ));

        command.arg("-Paws.fullsdk=true");
        command.arg(format!(
            "-Paws.sdk.previous.release.versions.manifest={}",
            self.previous_versions_manifest
                .to_str()
                .expect("not expecting strange file names")
        ));
        command.arg(format!(
            "-Paws.sdk.examples.revision={}",
            &self.aws_doc_sdk_examples_revision
        ));
        command.arg("aws:sdk:assemble");
        command.current_dir(self.smithy_rs.path());

        let output = command.output()?;
        handle_failure("aws_sdk_assemble", &output)?;
        Ok(())
    }
}

impl SdkGenerator for DefaultSdkGenerator {
    #[instrument(skip(self))]
    fn generate_sdk(&self) -> Result<GeneratedSdk> {
        self.copy_examples().context(here!())?;
        self.aws_sdk_assemble().context(here!())?;
        Ok(GeneratedSdk {
            path: self.smithy_rs.path().join("aws/sdk/build/aws-sdk"),
            _temp_dir: Some(self.temp_dir.clone()),
        })
    }
}
