/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An object that represents a collection of Android NDK source code.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/jni/BUCK</code>:
 * <pre>
 * ndk_library(
 *   name = 'feed-jni',
 *   deps = [],
 *   flags = ["NDK_DEBUG=1", "V=1"],
 * )
 * </pre>
 */
public class NdkLibrary extends AbstractBuildRule
    implements NativeLibraryBuildRule, AndroidPackageable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /** @see NativeLibraryBuildRule#isAsset() */
  private final boolean isAsset;

  /** The directory containing the Android.mk file to use. This value includes a trailing slash. */
  private final String makefileDirectory;
  private final String lastPathComponent;
  private final Path buildArtifactsDirectory;
  private final Path genDirectory;

  private final ImmutableSortedSet<SourcePath> sources;
  private final ImmutableList<String> flags;
  private final Optional<String> ndkVersion;

  protected NdkLibrary(
      BuildRuleParams params,
      Set<SourcePath> sources,
      List<String> flags,
      boolean isAsset,
      Optional<String> ndkVersion) {
    super(params);
    this.isAsset = isAsset;

    BuildTarget buildTarget = params.getBuildTarget();
    this.makefileDirectory = buildTarget.getBasePathWithSlash();
    this.lastPathComponent = "__lib" + buildTarget.getShortName();
    this.buildArtifactsDirectory = getBuildArtifactsDirectory(buildTarget, true /* isScratchDir */);
    this.genDirectory = getBuildArtifactsDirectory(buildTarget, false /* isScratchDir */);

    Preconditions.checkArgument(!sources.isEmpty(),
        "Must include at least one file (Android.mk?) in ndk_library rule");
    this.sources = ImmutableSortedSet.copyOf(sources);
    this.flags = ImmutableList.copyOf(flags);

    this.ndkVersion = Preconditions.checkNotNull(ndkVersion);
  }

  @Override
  public boolean isAsset() {
    return isAsset;
  }

  @Override
  public Path getLibraryPath() {
    return genDirectory;
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    // An ndk_library() does not have a "primary output" at this time.
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // .so files are written to the libs/ subdirectory of the output directory.
    // All of them should be recorded via the BuildableContext.
    Path binDirectory = buildArtifactsDirectory.resolve("libs");
    Step nkdBuildStep = new NdkBuildStep(makefileDirectory,
        buildArtifactsDirectory,
        binDirectory,
        flags);

    Step mkDirStep = new MakeCleanDirectoryStep(genDirectory);
    Step copyStep = CopyStep.forDirectory(
        binDirectory,
        genDirectory,
        CopyStep.DirectoryMode.CONTENTS_ONLY);
    buildableContext.recordArtifactsInDirectory(genDirectory);
    return ImmutableList.of(nkdBuildStep, mkDirStep, copyStep);
  }

  /**
   * @param isScratchDir true if this should be the "working directory" where a build rule may write
   *     intermediate files when computing its output. false if this should be the gen/ directory
   *     where the "official" outputs of the build rule should be written. Files of the latter type
   *     can be referenced via the genfile() function.
   */
  private Path getBuildArtifactsDirectory(BuildTarget target, boolean isScratchDir) {
    return Paths.get(
        isScratchDir ? BuckConstant.BIN_DIR : BuckConstant.GEN_DIR,
        target.getBasePath(),
        lastPathComponent);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("ndk_version", ndkVersion.or("NONE"))
        .set("flags", flags)
        .set("is_asset", isAsset());
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(sources);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (isAsset) {
      collector.addNativeLibAssetsDirectory(getLibraryPath());
    } else {
      collector.addNativeLibsDirectory(getLibraryPath());
    }
  }
}
