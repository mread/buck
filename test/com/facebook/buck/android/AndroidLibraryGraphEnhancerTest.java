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

import static com.facebook.buck.android.AndroidLibraryGraphEnhancer.Result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JavaAbiRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AndroidLibraryGraphEnhancerTest {

  @Test
  public void testEmptyResources() {
    BuildTarget buildTarget = new BuildTarget("//java/com/example", "library");
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget);
    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget,
        buildRuleParams,
        new FakeAbstractBuildRuleBuilderParams());
    Result result = graphEnhancer.createBuildableForAndroidResources(new BuildRuleResolver(),
        /* createdBuildableIfEmptyDeps */ false);
    assertFalse(result.getOptionalDummyRDotJava().isPresent());
    assertEquals(buildRuleParams, result.getBuildRuleParams());
  }

  @Test
  public void testBuildableIsCreated() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    AndroidResourceRule resourceRule1 = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res1")));
    AndroidResourceRule resourceRule2 = ruleResolver.buildAndAddToIndex(
        AndroidResourceRule.newAndroidResourceRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
            .setRDotJavaPackage("com.facebook")
            .setRes(Paths.get("android_res/com/example/res2")));

    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        buildTarget,
        ImmutableSortedSet.<BuildRule>of(resourceRule1, resourceRule2));

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        buildTarget, buildRuleParams, new FakeAbstractBuildRuleBuilderParams());
    Result result = graphEnhancer.createBuildableForAndroidResources(ruleResolver,
        /* createBuildableIfEmptyDeps */ false);

    assertTrue(result.getOptionalDummyRDotJava().isPresent());
    assertEquals(
        "DummyRDotJava must contain these exact AndroidResourceRules.",
        ImmutableList.of(resourceRule1, resourceRule2),
        result.getOptionalDummyRDotJava().get().getAndroidResourceDeps());

    ImmutableSortedSet<BuildRule> newDeps = result.getBuildRuleParams().getDeps();
    assertEquals("BuildRuleParams in the result object must have DummyRDotJava as a dependency.",
        3, newDeps.size());

    BuildRule dummyRDotJavaRule = newDeps.last();
    assertTrue(dummyRDotJavaRule instanceof JavaAbiRule);
    assertEquals("//java/com/example:library#dummy_r_dot_java",
        dummyRDotJavaRule.getFullyQualifiedName());
    assertEquals("DummyRDotJava must depend on the two AndroidResourceRules.",
        ImmutableSet.of("//android_res/com/example:res1", "//android_res/com/example:res2"),
        FluentIterable.from(dummyRDotJavaRule.getDeps())
            .transform(Functions.toStringFunction()).toSet());
  }
}
