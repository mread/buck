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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Paths;

public class FakeBuildRuleParamsBuilder {

  private final BuildTarget buildTarget;
  private ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of();
  private ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.of();
  private ImmutableSet<BuildTargetPattern> visibilityPatterns = BuildTargetPattern.PUBLIC;
  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private FileHashCache fileHashCache =
      new DefaultFileHashCache(new ProjectFilesystem(Paths.get(".")), new TestConsole());

  private BuildRuleType buildRuleType = new BuildRuleType("fake_build_rule");

  public FakeBuildRuleParamsBuilder(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
  }

  public FakeBuildRuleParamsBuilder(String buildTarget) {
    this(BuildTargetFactory.newInstance(Preconditions.checkNotNull(buildTarget)));
  }

  public FakeBuildRuleParamsBuilder setDeps(ImmutableSortedSet<BuildRule> deps) {
    this.deps = deps;
    return this;
  }

  public FakeBuildRuleParamsBuilder setExtraDeps(ImmutableSortedSet<BuildRule> extraDeps) {
    this.extraDeps = extraDeps;
    return this;
  }

  public FakeBuildRuleParamsBuilder setVisibility(ImmutableSet<BuildTargetPattern> patterns) {
    this.visibilityPatterns = patterns;
    return this;
  }

  public FakeBuildRuleParamsBuilder setProjectFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    return this;
  }

  public FakeBuildRuleParamsBuilder setFileHashCache(FileHashCache hashCache) {
    this.fileHashCache = hashCache;
    return this;
  }

  public FakeBuildRuleParamsBuilder setType(BuildRuleType type) {
    this.buildRuleType = type;
    return this;
  }

  public BuildRuleParams build() {
    return new BuildRuleParams(
        buildTarget,
        deps,
        extraDeps,
        visibilityPatterns,
        filesystem,
        new FakeRuleKeyBuilderFactory(fileHashCache),
        buildRuleType);
  }
}
