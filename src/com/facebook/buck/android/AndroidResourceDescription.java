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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class AndroidResourceDescription implements Description<AndroidResourceDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_resource");

  private static final ImmutableSet<String> NON_ASSET_FILENAMES =
      ImmutableSet.of(".svn", ".git", ".ds_store", ".scc", "cvs", "thumbs.db", "picasa.ini");

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
    ProjectFilesystem filesystem = params.getProjectFilesystem();

    return new AndroidResource(
        params,
        args.deps.get(),
        args.res.orNull(),
        collectInputFiles(filesystem, args.res),
        args.rDotJavaPackage.orNull(),
        args.assets.orNull(),
        collectInputFiles(filesystem, args.assets),
        args.manifest.orNull(),
        args.hasWhitelistedStrings.or(false));
  }

  @VisibleForTesting
  ImmutableSortedSet<Path> collectInputFiles(
      ProjectFilesystem filesystem,
      Optional<Path> inputDir) {
    if (!inputDir.isPresent()) {
      return ImmutableSortedSet.of();
    }
    final ImmutableSortedSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();

    // aapt, unless specified a pattern, ignores certain files and directories. We follow the same
    // logic as the default pattern found at http://goo.gl/OTTK88 and line 61.
    FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(
          Path dir,
          BasicFileAttributes attr) throws IOException {
        String dirName = dir.getFileName().toString();
        // Special case: directory starting with '_' as per aapt.
        if (dirName.charAt(0) == '_' || !isResource(dirName)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
        String filename = file.getFileName().toString();
        if (isResource(filename)) {
          paths.add(file);
        }
        return FileVisitResult.CONTINUE;
      }

      private boolean isResource(String fileOrDirName) {
        if (NON_ASSET_FILENAMES.contains(fileOrDirName.toLowerCase())) {
          return false;
        }
        if (fileOrDirName.charAt(fileOrDirName.length() - 1) == '~') {
          return false;
        }
        return true;
      }
    };

    try {
      filesystem.walkRelativeFileTree(inputDir.get(), fileVisitor);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory: %s.", inputDir.get());
    }
    return paths.build();
  }

  public static class Arg implements ConstructorArg {
    public Optional<Path> res;
    public Optional<Path> assets;
    public Optional<Boolean> hasWhitelistedStrings;
    @Hint(name = "package")
    public Optional<String> rDotJavaPackage;
    public Optional<Path> manifest;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
