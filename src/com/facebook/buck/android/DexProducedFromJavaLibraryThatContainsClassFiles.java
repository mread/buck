/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.DexProducedFromJavaLibraryThatContainsClassFiles.BuildOutput;
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * {@link DexProducedFromJavaLibraryThatContainsClassFiles} is a {@link Buildable} that serves a
 * very specific purpose: it takes a {@link JavaLibraryRule} and dexes the
 * output of the {@link JavaLibraryRule} if its list of classes is non-empty. Because it is
 * expected to be used with pre-dexing, we always pass the {@code --force-jumbo} flag to {@code dx}
 * in this buildable.
 * <p>
 * Most {@link Buildable}s can determine the (possibly null) path to their output file from their
 * definition. This is an anomaly because we do not know whether this will write a {@code .dex} file
 * until runtime. Unfortunately, because there is no such thing as an empty {@code .dex} file, we
 * cannot write a meaningful "dummy .dex" if there are no class files to pass to {@code dx}.
 */
public class DexProducedFromJavaLibraryThatContainsClassFiles extends AbstractBuildable
    implements InitializableFromDisk<BuildOutput> {

  @VisibleForTesting
  static final String LINEAR_ALLOC_KEY_ON_DISK_METADATA = "linearalloc";

  private final BuildTarget buildTarget;
  private final JavaLibraryRule javaLibrary;

  @Nullable private BuildOutput buildOutput;

  @VisibleForTesting
  DexProducedFromJavaLibraryThatContainsClassFiles(BuildTarget buildTarget,
      JavaLibraryRule javaLibrary) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.javaLibrary = Preconditions.checkNotNull(javaLibrary);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    // The deps of this rule already capture all of the inputs that should affect the cache key.
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getPathToDex(), /* shouldForceDeletion */ true));

    // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
    steps.add(new MkdirStep(getPathToDex().getParent()));

    // If there are classes, run dx.
    final boolean hasClassesToDx = !javaLibrary.getClassNamesToHashes().isEmpty();
    final Supplier<Integer> linearAllocEstimate;
    if (hasClassesToDx) {
      Path pathToOutputFile = javaLibrary.getPathToOutputFile();
      EstimateLinearAllocStep estimate = new EstimateLinearAllocStep(pathToOutputFile);
      steps.add(estimate);
      linearAllocEstimate = estimate;

      // To be conservative, use --force-jumbo for these intermediate .dex files so that they can be
      // merged into a final classes.dex that uses jumbo instructions.
      DxStep dx = new DxStep(getPathToDex(),
          Collections.singleton(pathToOutputFile),
          EnumSet.of(DxStep.Option.NO_OPTIMIZE, DxStep.Option.FORCE_JUMBO));
      steps.add(dx);
    } else {
      linearAllocEstimate = Suppliers.ofInstance(0);
    }

    // Run a step to record artifacts and metadata. The values recorded depend upon whether dx was
    // run.
    String stepName = hasClassesToDx ? "record_dx_success" : "record_empty_dx";
    AbstractExecutionStep recordArtifactAndMetadataStep = new AbstractExecutionStep(stepName) {
      @Override
      public int execute(ExecutionContext context) {
        if (hasClassesToDx) {
          buildableContext.recordArtifact(getPathToDex());
        }

        buildableContext.addMetadata(LINEAR_ALLOC_KEY_ON_DISK_METADATA,
            String.valueOf(linearAllocEstimate.get()));
        return 0;
      }
    };
    steps.add(recordArtifactAndMetadataStep);

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    int linearAllocEstimate = Integer.parseInt(
        onDiskBuildInfo.getValue(LINEAR_ALLOC_KEY_ON_DISK_METADATA).get());
    return new BuildOutput(linearAllocEstimate);
  }

  @Override
  public void setBuildOutput(BuildOutput buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public BuildOutput getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  static class BuildOutput {
    private final int linearAllocEstimate;
    private BuildOutput(int linearAllocEstimate) {
      this.linearAllocEstimate = linearAllocEstimate;
    }
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  public Path getPathToDex() {
    return Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        buildTarget.getShortName() + ".dex.jar");
  }

  public boolean hasOutput() {
    return !getClassNames().isEmpty();
  }

  ImmutableSortedMap<String, HashCode> getClassNames() {
    // TODO(mbolin): Assert that this Buildable has been built. Currently, there is no way to do
    // that from a Buildable (but there is from an AbstractCachingBuildRule).
    return javaLibrary.getClassNamesToHashes();
  }

  int getLinearAllocEstimate() {
    return getBuildOutput().linearAllocEstimate;
  }

  /**
   * The only dep for this rule should be {@link #javaLibrary}. Therefore, the ABI key for the deps
   * of this buildable is the hash of the {@code .class} files for {@link #javaLibrary}.
   */
  Sha1HashCode getAbiKeyForDeps() {
    return computeAbiKey(javaLibrary.getClassNamesToHashes());
  }

  @VisibleForTesting
  static Sha1HashCode computeAbiKey(ImmutableSortedMap<String, HashCode> classNames) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (Map.Entry<String, HashCode> entry : classNames.entrySet()) {
      hasher.putUnencodedChars(entry.getKey());
      hasher.putByte((byte)0);
      hasher.putUnencodedChars(entry.getValue().toString());
      hasher.putByte((byte)0);
    }
    return new Sha1HashCode(hasher.hash().toString());
  }
}
