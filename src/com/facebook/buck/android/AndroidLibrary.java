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

import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

public class AndroidLibrary extends DefaultJavaLibrary implements AndroidPackageable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  private final Optional<SourcePath> manifestFile;

  private final boolean isPrebuiltAar;

  @VisibleForTesting
  public AndroidLibrary(
      BuildRuleParams params,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      ImmutableSet<Path> additionalClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      boolean isPrebuiltAar) {
    super(
        params,
        srcs,
        resources,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        additionalClasspathEntries,
        javacOptions,
        resourcesRoot);
    this.manifestFile = Preconditions.checkNotNull(manifestFile);
    this.isPrebuiltAar = isPrebuiltAar;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public Optional<SourcePath> getManifestFile() {
    return manifestFile;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    if (manifestFile.isPresent()) {
      return ImmutableList.<Path>builder()
          .addAll(super.getInputsToCompareToOutput())
          .addAll(
              SourcePaths.filterInputsToCompareToOutput(Collections.singleton(manifestFile.get())))
          .build();
    } else {
      return super.getInputsToCompareToOutput();
    }
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    super.addToCollector(collector);
    if (manifestFile.isPresent()) {
      collector.addManifestFile(getBuildTarget(), manifestFile.get().resolve());
    }
  }

  /** @return whether this library was generated from an {@link AndroidPrebuiltAarDescription}. */
  public boolean isPrebuiltAar() {
    return isPrebuiltAar;
  }
}
