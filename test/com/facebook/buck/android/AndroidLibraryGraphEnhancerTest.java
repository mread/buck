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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacVersion;
import com.facebook.buck.java.PopularAndroidJavaCompilerEnvironment;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AndroidLibraryGraphEnhancerTest {

  @Test
  public void testEmptyResources() {
    BuildTarget buildTarget = BuildTarget.builder("//java/com/example", "library").build();
    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        JavacOptions.DEFAULTS);
    Optional<DummyRDotJava> result = graphEnhancer.createBuildableForAndroidResources(
        new BuildRuleResolver(),
        /* createdBuildableIfEmptyDeps */ false);
    assertFalse(result.isPresent());
  }

  @Test
  public void testBuildableIsCreated() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1"))
            .build());
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2"))
            .build());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(resourceRule1, resourceRule2))
        .build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        buildRuleParams,
        JavacOptions.DEFAULTS);
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.createBuildableForAndroidResources(
        ruleResolver,
        /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    assertEquals(
        "DummyRDotJava must contain these exact AndroidResourceRules.",
        // Note: these are the reverse order to which they are in the buildRuleParams.
        ImmutableList.of(resourceRule1, resourceRule2),
        dummyRDotJava.get().getAndroidResourceDeps());

    assertEquals("//java/com/example:library#dummy_r_dot_java",
        dummyRDotJava.get().getFullyQualifiedName());
    assertEquals(
        "DummyRDotJava must depend on the two AndroidResourceRules.",
        ImmutableSet.of("//android_res/com/example:res1", "//android_res/com/example:res2"),
        FluentIterable.from(dummyRDotJava.get().getDeps())
            .transform(Functions.toStringFunction()).toSet());
  }

  @Test
  public void testCreatedBuildableHasOverriddenJavacConfig() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule resourceRule1 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1"))
            .build());
    BuildRule resourceRule2 = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2"))
            .build());

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(resourceRule1, resourceRule2))
        .build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        buildRuleParams,
        JavacOptions.builder(JavacOptions.DEFAULTS)
            .setJavaCompilerEnviornment(
                new JavaCompilerEnvironment(
                    Optional.of(Paths.get("javac")),
                    Optional.of(new JavacVersion("1.7")),
                    PopularAndroidJavaCompilerEnvironment.TARGETED_JAVA_VERSION,
                    PopularAndroidJavaCompilerEnvironment.TARGETED_JAVA_VERSION))
            .build());
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.createBuildableForAndroidResources(
        ruleResolver,
        /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    JavacOptions javacOptions = dummyRDotJava.get().getJavacOptions();
    assertEquals(
        Paths.get("javac"),
        javacOptions.getJavaCompilerEnvironment().getJavacPath().get());
    assertEquals(
        new JavacVersion("1.7"),
        javacOptions.getJavaCompilerEnvironment().getJavacVersion().get());
  }
}
