/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cpp.AbstractNativeBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule that has configuration ready for Xcode-like build systems.
 */
public abstract class AbstractAppleNativeTargetBuildRule extends AbstractNativeBuildRule {

  private final Optional<Path> infoPlist;
  private final ImmutableSet<XcodeRuleConfiguration> configurations;
  private final ImmutableList<GroupedSource> srcs;
  private final ImmutableMap<SourcePath, String> perFileFlags;
  private final ImmutableSortedSet<String> frameworks;

  public AbstractAppleNativeTargetBuildRule(
      BuildRuleParams params,
      AppleNativeTargetDescriptionArg arg,
      TargetSources targetSources) {
    super(params, targetSources.srcPaths, targetSources.headerPaths, targetSources.perFileFlags);
    infoPlist = arg.infoPlist;
    configurations = XcodeRuleConfiguration.fromRawJsonStructure(arg.configs);
    frameworks = Preconditions.checkNotNull(arg.frameworks);
    srcs = Preconditions.checkNotNull(targetSources.srcs);
    perFileFlags = Preconditions.checkNotNull(targetSources.perFileFlags);
  }

  /**
   * Returns a path to the info.plist to be bundled with a binary or framework.
   */
  public Optional<Path> getInfoPlist() {
    return infoPlist;
  }

  /**
   * Returns a set of Xcode configuration rules.
   */
  public ImmutableSet<XcodeRuleConfiguration> getConfigurations() {
    return configurations;
  }

  /**
   * Returns a list of sources, potentially grouped for display in Xcode.
   */
  public ImmutableList<GroupedSource> getSrcs() {
    return srcs;
  }

  /**
   * Returns a list of per-file build flags, e.g. -fobjc-arc.
   */
  public ImmutableMap<SourcePath, String> getPerFileFlags() {
    return perFileFlags;
  }

  /**
   * Returns the set of frameworks to link with the target.
   */
  public ImmutableSortedSet<String> getFrameworks() {
    return frameworks;
  }

  @Override
  protected String getCompiler() {
    return "clang";
  }
}
