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
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CopyResourcesStep implements Step {

  /**
   * This matches the Path for a BuildRuleSourcePath and returns the path without the
   * "buck-out/XXX/" component as the first capturing group.
   */
  private static final Pattern GENERATED_FILE_PATTERN = Pattern.compile(
      "^(?:" +
          Joiner.on('|').join(
              BuckConstant.ANNOTATION_DIR,
              BuckConstant.BIN_DIR,
              BuckConstant.GEN_DIR
          ) + ")/(.*)");

  private final BuildTarget target;
  private final Collection<? extends SourcePath> resources;
  private final Path outputDirectory;
  private final JavaPackageFinder javaPackageFinder;

  public CopyResourcesStep(
      BuildTarget target,
      Collection<? extends SourcePath> resources,
      Path outputDirectory,
      JavaPackageFinder javaPackageFinder) {
    this.target = Preconditions.checkNotNull(target);
    this.resources = Preconditions.checkNotNull(resources);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    for (Step step : buildSteps()) {
      int result = step.execute(context);
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  @VisibleForTesting
  ImmutableList<Step> buildSteps() {
    ImmutableList.Builder<Step> allSteps = ImmutableList.builder();

    if (resources.isEmpty()) {
      return allSteps.build();
    }

    String targetPackageDir = javaPackageFinder.findJavaPackageForPath(
        target.getBasePathWithSlash())
        .replace('.', File.separatorChar);

    for (SourcePath rawResource : resources) {
      // If the path to the file defining this rule were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/BUCK"
      //
      // And the value of resource were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Assuming that `src_roots = tests` were in the [java] section of the .buckconfig file,
      // then javaPackageAsPath would be:
      // "com/facebook/orca/protocol/base/"
      //
      // And the path that we would want to copy to the classes directory would be:
      // "com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Therefore, some path-wrangling is required to produce the correct string.

      final Path pathToResource = rawResource.resolve();
      String resource = MorePaths.pathWithUnixSeparators(pathToResource);
      Matcher matcher;
      if ((matcher = GENERATED_FILE_PATTERN.matcher(resource)).matches()) {
        resource = matcher.group(1);
      }
      String javaPackageAsPath = javaPackageFinder.findJavaPackageFolderForPath(resource);

      Path relativeSymlinkPath;
      if ("".equals(javaPackageAsPath)) {
        // In this case, the project root is acting as the default package, so the resource path
        // works fine.
        relativeSymlinkPath = pathToResource.getFileName();
      } else {
        int lastIndex = resource.lastIndexOf(javaPackageAsPath);
        if (lastIndex < 0) {
          Preconditions.checkState(
              rawResource instanceof BuildRuleSourcePath,
              "If resource path %s does not contain %s, then it must be a BuildRuleSourcePath.",
              pathToResource,
              javaPackageAsPath);
          // Handle the case where we depend on the output of another BuildRule. In that case, just
          // grab the output and put in the same package as this target would be in.
          relativeSymlinkPath = Paths.get(
              String.format(
                  "%s%s%s",
                  targetPackageDir,
                  targetPackageDir.isEmpty() ? "" : "/",
                  rawResource.resolve().getFileName()));
        } else {
          relativeSymlinkPath = Paths.get(resource.substring(lastIndex));
        }
      }
      Path target = outputDirectory.resolve(relativeSymlinkPath);
      MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(pathToResource, target);
      allSteps.add(link);
    }
    return allSteps.build();
  }

  @Override
  public String getShortName() {
    return "copy_resources";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s of %s", getShortName(), target);
  }
}
