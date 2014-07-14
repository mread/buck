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

package com.facebook.buck.android;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class RobolectricTestDescription implements Description<RobolectricTestDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("robolectric_test");
  private final JavaCompilerEnvironment javacEnv;

  public RobolectricTestDescription(JavaCompilerEnvironment javacEnv) {
    this.javacEnv = javacEnv;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> RobolectricTest createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    JavacOptions.Builder javacOptions = JavaLibraryDescription.getJavacOptions(args, javacEnv);

    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(params.getBuildTarget());
    javacOptions.setAnnotationProcessingData(annotationParams);

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(args.exportedDeps.get()),
        javacOptions.build());
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.createBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ true);

    ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
    if (dummyRDotJava.isPresent()) {
      additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getRDotJavaBinFolder());
      ImmutableSortedSet<BuildRule> newExtraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getExtraDeps())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyWithExtraDeps(newExtraDeps);
    }

    return new RobolectricTest(
        params,
        args.srcs.get(),
        JavaLibraryDescription.validateResources(
            args,
            params.getProjectFilesystem()),
        args.labels.get(),
        args.contacts.get(),
        args.proguardConfig,
        additionalClasspathEntries,
        javacOptions.build(),
        args.vmArgs.get(),
        JavaTestDescription.validateAndGetSourcesUnderTest(
            args.sourceUnderTest.get(),
            params.getBuildTarget(),
            resolver),
        args.resourcesRoot,
        dummyRDotJava);
  }

  public class Arg extends JavaTestDescription.Arg {
  }
}
