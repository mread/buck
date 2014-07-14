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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FlavorableDescription;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collections;

public class PrebuiltJarDescription implements Description<PrebuiltJarDescription.Arg>,
    FlavorableDescription<PrebuiltJarDescription.Arg>{

  public static class Arg implements ConstructorArg {
    public SourcePath binaryJar;
    public Optional<SourcePath> sourceJar;
    public Optional<SourcePath> gwtJar;
    public Optional<String> javadocUrl;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_jar");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new PrebuiltJar(
        params,
        args.binaryJar,
        args.sourceJar,
        args.gwtJar,
        args.javadocUrl);
  }

  @Override
  public void registerFlavors(
      Arg arg,
      BuildRule buildRule,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleResolver ruleResolver) {
    BuildTarget prebuiltJarBuildTarget = buildRule.getBuildTarget();
    BuildTarget flavoredBuildTarget = BuildTargets.createFlavoredBuildTarget(
        prebuiltJarBuildTarget, JavaLibrary.GWT_MODULE_FLAVOR);
    BuildRuleParams params = new BuildRuleParams(
        flavoredBuildTarget,
        /* declaredDeps */ ImmutableSortedSet.of(buildRule),
        /* inferredDeps */ ImmutableSortedSet.<BuildRule>of(),
        BuildTargetPattern.PUBLIC,
        projectFilesystem,
        ruleKeyBuilderFactory,
        BuildRuleType.GWT_MODULE);
    BuildRule gwtModule = createGwtModule(params, arg);
    ruleResolver.addToIndex(gwtModule.getBuildTarget(), gwtModule);
  }

  @VisibleForTesting
  static BuildRule createGwtModule(BuildRuleParams params, Arg arg) {
    // Because a PrebuiltJar rarely requires any building whatsoever (it could if the source_jar
    // is a BuildRuleSourcePath), we make the PrebuiltJar a dependency of the GWT module. If this
    // becomes a performance issue in practice, then we will explore reducing the dependencies of
    // the GWT module.
    final SourcePath inputToCompareToOutput;
    if (arg.gwtJar.isPresent()) {
      inputToCompareToOutput = arg.gwtJar.get();
    } else if (arg.sourceJar.isPresent()) {
      inputToCompareToOutput = arg.sourceJar.get();
    } else {
      inputToCompareToOutput = arg.binaryJar;
    }
    final ImmutableCollection<Path> inputsToCompareToOutput =
        SourcePaths.filterInputsToCompareToOutput(Collections.singleton(inputToCompareToOutput));
    final Path pathToExistingJarFile = inputToCompareToOutput.resolve();

    BuildRule buildRule = new AbstractBuildRule(params) {
      @Override
      protected Iterable<Path> getInputsToCompareToOutput() {
        return inputsToCompareToOutput;
      }

      @Override
      protected Builder appendDetailsToRuleKey(Builder builder) {
        return builder;
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context,
          BuildableContext buildableContext) {
        buildableContext.recordArtifact(getPathToOutputFile());
        return ImmutableList.of();
      }

      @Override
      public Path getPathToOutputFile() {
        return pathToExistingJarFile;
      }
    };
    return buildRule;
  }
}
