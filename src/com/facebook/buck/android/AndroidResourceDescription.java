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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import javax.annotation.Nullable;

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

  /**
   * Filters out the set of {@code android_resource()} dependencies from {@code deps}. As a special
   * case, if an {@code android_prebuilt_aar()} appears in the deps, the {@code android_resource()}
   * that corresponds to the AAR will also be included in the output.
   * <p>
   * Note that if we allowed developers to depend on a flavored build target (in this case, the
   * {@link AndroidPrebuiltAarGraphEnhancer#AAR_ANDROID_RESOURCE_FLAVOR} flavor), then we could
   * require them to depend on the flavored dep explicitly in their build files. Then we could
   * eliminate this special case, though it would be more burdensome for developers to have to
   * keep track of when they could depend on an ordinary build rule vs. a flavored one.
   */
  private static ImmutableSortedSet<BuildRule> androidResOnly(ImmutableSortedSet<BuildRule> deps) {
    return FluentIterable
        .from(deps)
        .transform(new Function<BuildRule, BuildRule>() {
          @Override
          @Nullable
          public BuildRule apply(BuildRule buildRule) {
            if (buildRule instanceof AndroidResource) {
              return buildRule;
            } else if (buildRule instanceof AndroidLibrary &&
                ((AndroidLibrary) buildRule).isPrebuiltAar()) {
              // An AndroidLibrary that is created via graph enhancement from an
              // android_prebuilt_aar() should always have exactly one dependency that is an
              // AndroidResource.
              return Iterables.getOnlyElement(
                  FluentIterable.from(buildRule.getDeps())
                      .filter(Predicates.instanceOf(AndroidResource.class))
                      .toList());
            }
            return null;
          }
        })
        .filter(Predicates.notNull())
        .toSortedSet(deps.comparator());
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    // Only allow android resource and library rules as dependencies.
    Optional<BuildRule> invalidDep = FluentIterable
        .from(Iterables.concat(params.getDeclaredDeps(), params.getExtraDeps()))
        .filter(Predicates.not(Predicates.or(
            Predicates.instanceOf(AndroidResource.class),
            Predicates.instanceOf(AndroidLibrary.class))))
        .first();
    if (invalidDep.isPresent()) {
      throw new HumanReadableException(
          params.getBuildTarget() + " (android_resource): dependency " +
              invalidDep.get().getBuildTarget() + " (" + invalidDep.get().getType() +
              ") is not of type android_resource or android_library.");
    }

    ProjectFilesystem filesystem = params.getProjectFilesystem();
    return new AndroidResource(
        // We only propagate other AndroidResource rule dependencies, as these are
        // the only deps which should control whether we need to re-run the aapt_package
        // step.
        params.copyWithDeps(
            androidResOnly(params.getDeclaredDeps()),
            androidResOnly(params.getExtraDeps())),
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
    public Optional<SourcePath> manifest;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
