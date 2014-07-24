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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Collections;

public class AndroidManifestDescription implements Description<AndroidManifestDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_manifest");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AndroidManifest createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ImmutableSet<SourcePath> manifestFiles = findManifestFiles(args);

    // The only rules that need to be built before this AndroidManifest are those
    // responsible for generating the AndroidManifest.xml files in the manifestFiles set (and
    // possibly the skeleton).
    //
    // If the skeleton is a BuildRuleSourcePath, then its build rule must also be in the deps.
    // The skeleton does not appear to be in either params.getDeclaredDeps() or
    // params.getExtraDeps(), even though the type of Arg.skeleton is SourcePath.
    // TODO(simons): t4744625 This should happen automagically.
    ImmutableSortedSet<BuildRule> newDeps = FluentIterable
        .from(Sets.union(manifestFiles, Collections.singleton(args.skeleton)))
        .filter(BuildRuleSourcePath.class)
        .transform(SourcePaths.TO_BUILD_RULE_REFERENCES)
        .toSortedSet(BuildTarget.BUILD_TARGET_COMPARATOR);

    return new AndroidManifest(
        params.copyWithDeps(newDeps, params.getExtraDeps()),
        args.skeleton,
        manifestFiles);
  }

  public static class Arg implements ConstructorArg {
    public SourcePath skeleton;

    /**
     * A collection of dependencies that includes android_library rules. The manifest files of the
     * android_library rules will be filtered out to become dependent source files for the
     * {@link AndroidManifest}.
     */
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

  @VisibleForTesting
  static ImmutableSet<SourcePath> findManifestFiles(Arg args) {
    AndroidTransitiveDependencyGraph transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(args.deps.get());
    return transitiveDependencyGraph.findManifestFiles();
  }
}
