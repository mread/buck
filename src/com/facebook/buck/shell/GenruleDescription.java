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

package com.facebook.buck.shell;


import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.regex.Matcher;

public class GenruleDescription
    implements Description<GenruleDescription.Arg>, ImplicitDepsInferringDescription {

  public static final BuildRuleType TYPE = new BuildRuleType("genrule");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> Genrule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ImmutableList<SourcePath> srcs = args.srcs.get();
    ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(params.getExtraDeps())
        .addAll(SourcePaths.filterBuildRuleInputs(srcs))
        .build();

    return new Genrule(
        params.copyWithExtraDeps(extraDeps),
        srcs,
        args.cmd,
        args.bash,
        args.cmdExe,
        args.out,
        params.getPathAbsolutifier());
  }

  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    ImmutableSet.Builder<String> targets = ImmutableSet.builder();
    addDepsFromParam(params, "bash", targets);
    addDepsFromParam(params, "cmd", targets);
    addDepsFromParam(params, "cmdExe", targets);
    return targets.build();
  }

  private static void addDepsFromParam(
      BuildRuleFactoryParams params,
      String paramName,
      ImmutableSet.Builder<String> targets) {
    Object rawCmd = params.getNullableRawAttribute(paramName);
    if (rawCmd == null) {
      return;
    }
    Matcher matcher = AbstractGenruleStep.BUILD_TARGET_PATTERN.matcher(((String) rawCmd));
    while (matcher.find()) {
      targets.add(matcher.group(3));
    }
  }

  @SuppressFieldNotInitialized
  public class Arg {
    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<SourcePath>> srcs;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
