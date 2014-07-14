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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link AndroidManifest}.
 */
public class AndroidManifestTest {

  /**
   * Tests the following methods:
   * <ul>
   *   <li>{@link AbstractBuildRule#getInputsToCompareToOutput()}
   *   <li>{@link AndroidManifest#getPathToOutputFile()}
   * </ul>
   */
  @Test
  public void testSimpleObserverMethods() {
    AndroidManifest androidManifest = createSimpleAndroidManifestRule();

    assertEquals(
        ImmutableList.of(Paths.get("java/com/example/AndroidManifestSkeleton.xml")),
        ImmutableList.copyOf(androidManifest.getInputsToCompareToOutput()));
    assertEquals(
        BuckConstant.GEN_PATH.resolve("java/com/example/AndroidManifest__manifest__.xml"),
        androidManifest.getPathToOutputFile());
  }

  @Test
  public void testBuildInternal() throws IOException {
    AndroidManifest androidManifest = createSimpleAndroidManifestRule();

    // Mock out a BuildContext whose DependencyGraph will be traversed.
    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    EasyMock.replay(buildContext);

    List<Step> steps = androidManifest.getBuildSteps(buildContext, new FakeBuildableContext());
    Step generateManifestStep = steps.get(2);
    assertEquals(
        new GenerateManifestStep(
            Paths.get("java/com/example/AndroidManifestSkeleton.xml"),
            /* libraryManifestPaths */ ImmutableSet.<Path>of(),
            BuckConstant.GEN_PATH.resolve("java/com/example/AndroidManifest__manifest__.xml")),
        generateManifestStep);

    EasyMock.verify(buildContext);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals("android_manifest", AndroidManifestDescription.TYPE.getName());
  }

  private AndroidManifest createSimpleAndroidManifestRule() {
    // First, create the AndroidManifest object.
    BuildRuleParams buildRuleParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTarget.builder("//java/com/example", "manifest").build());
    AndroidManifestDescription description = new AndroidManifestDescription();
    AndroidManifestDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.skeleton = new TestSourcePath("java/com/example/AndroidManifestSkeleton.xml");
    arg.deps = Optional.<ImmutableSortedSet<BuildRule>>of(ImmutableSortedSet.<BuildRule>of());
    return description.createBuildRule(buildRuleParams, new BuildRuleResolver(), arg);
  }

  // TODO(user): Add another unit test that passes in a non-trivial DependencyGraph and verify that
  // the resulting set of libraryManifestPaths is computed correctly.
}
