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

import static com.facebook.buck.util.BuckConstant.BIN_PATH;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

public class CopyResourcesStepTest {
  @Test
  public void testAddResourceCommandsWithBuildFileParentOfSrcDirectory() {
    // Files:
    // android/java/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTarget.builder("//android/java", "resources").build();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();

    CopyResourcesStep step = new CopyResourcesStep(
        buildTarget,
        ImmutableSet.of(
            new TestSourcePath("android/java/src/com/facebook/base/data.json"),
            new TestSourcePath("android/java/src/com/facebook/common/util/data.json")),
        BIN_PATH.resolve("android/java/lib__resources__classes"),
        javaPackageFinder);

    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve("android/java/lib__resources__classes/com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/lib__resources__classes/com/facebook/common/util/data.json")));
    assertEquals(expected, step.buildSteps());
  }

  @Test
  public void testAddResourceCommandsWithBuildFileParentOfJavaPackage() {
    // Files:
    // android/java/src/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget = BuildTarget.builder("//android/java/src", "resources").build();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();

    CopyResourcesStep step = new CopyResourcesStep(
        buildTarget,
        ImmutableSet.<SourcePath>of(
            new TestSourcePath("android/java/src/com/facebook/base/data.json"),
            new TestSourcePath("android/java/src/com/facebook/common/util/data.json")),
        BIN_PATH.resolve("android/java/src/lib__resources__classes"),
        javaPackageFinder);

    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve(
                "android/java/src/lib__resources__classes/com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/src/lib__resources__classes/com/facebook/common/util/data.json")));
    assertEquals(expected, step.buildSteps());
  }

  @Test
  public void testAddResourceCommandsWithBuildFileInJavaPackage() {
    // Files:
    // android/java/src/com/facebook/BUILD
    // android/java/src/com/facebook/base/data.json
    // android/java/src/com/facebook/common/util/data.json
    BuildTarget buildTarget =
        BuildTarget.builder("//android/java/src/com/facebook", "resources").build();
    JavaPackageFinder javaPackageFinder = createJavaPackageFinder();

    CopyResourcesStep step = new CopyResourcesStep(
        buildTarget,
        ImmutableSet.of(
            new TestSourcePath("android/java/src/com/facebook/base/data.json"),
            new TestSourcePath("android/java/src/com/facebook/common/util/data.json")),
        BIN_PATH.resolve("android/java/src/com/facebook/lib__resources__classes"),
        javaPackageFinder);

    List<? extends Step> expected = ImmutableList.of(
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/base/data.json"),
            BIN_PATH.resolve(
                "android/java/src/com/facebook/lib__resources__classes/" +
                    "com/facebook/base/data.json")),
        new MkdirAndSymlinkFileStep(
            Paths.get("android/java/src/com/facebook/common/util/data.json"),
            BIN_PATH.resolve(
                "android/java/src/com/facebook/lib__resources__classes/" +
                    "com/facebook/common/util/data.json")));
    assertEquals(expected, step.buildSteps());
  }

  private JavaPackageFinder createJavaPackageFinder() {
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
        ImmutableSet.of("/android/java/src"));
  }
}
