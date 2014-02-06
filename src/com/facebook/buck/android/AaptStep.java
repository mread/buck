/*
 * Copyright 2012-present Facebook, Inc.
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

import com.android.sdklib.build.ApkBuilder;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Set;

/**
 * Runs the Android Asset Packaging Tool ({@code aapt}), which creates an {@code .apk} file.
 * Frequently, the {@code pathsToRawFilesDirs} excludes {@code classes.dex}, as {@code classes.dex}
 * will be added separately to the final APK via {@link ApkBuilder}.
 */
public class AaptStep extends ShellStep {

  private final Path androidManifest;
  // TODO(simons): Set<Path>
  private final Set<String> resDirectories;
  private final Optional<Path> assetsDirectory;
  private final Path pathToOutputApkFile;

  @SuppressWarnings("unused")
  private final boolean isCrunchPngFiles;

  public AaptStep(
      Path androidManifest,
      Set<String> resDirectories,
      Optional<Path> assetsDirectory,
      Path pathToOutputApkFile,
      boolean isCrunchPngFiles) {
    this.androidManifest = Preconditions.checkNotNull(androidManifest);
    this.resDirectories = ImmutableSet.copyOf(resDirectories);
    this.assetsDirectory = Preconditions.checkNotNull(assetsDirectory);
    this.pathToOutputApkFile = Preconditions.checkNotNull(pathToOutputApkFile);
    this.isCrunchPngFiles = isCrunchPngFiles;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
    builder.add(androidPlatformTarget.getAaptExecutable().getAbsolutePath(), "package");

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-v");
    }

    // Force overwrite of existing files.
    builder.add("-f");

    /*
     * In practice, it appears that if --no-crunch is used, resources will occasionally appear
     * distorted in the APK produced by this command (and what's worse, a clean reinstall does not
     * make the problem go away). This is not reliably reproducible, so for now, we categorically
     * outlaw the use of --no-crunch so that developers do not get stuck in the distorted image
     * state. One would expect the use of --no-crunch to allow for faster build times, so it would
     * be nice to figure out a way to leverage it in debug mode that never results in distorted
     * images.
     */
    // --no-crunch, if appropriate.
    // if (!isCrunchPngFiles) {
    //   builder.add("--no-crunch");
    // }

    // Include all of the res/ directories.
    builder.add("--auto-add-overlay");
    for (String res : resDirectories) {
      builder.add("-S", res);
    }

    // Include the assets/ directory, if any.
    // According to the aapt documentation, it appears that it should be possible to specify the -A
    // flag multiple times; however, in practice, when it is specified multiple times, only one of
    // the folders is included in the final APK.
    if (assetsDirectory.isPresent()) {
      builder.add("-A", assetsDirectory.get().toString());
    }

    builder.add("-M").add(androidManifest.toString());
    builder.add("-I", androidPlatformTarget.getAndroidJar().getAbsolutePath());
    builder.add("-F", pathToOutputApkFile.toString());

    return builder.build();
  }

  @Override
  public String getShortName() {
    return String.format("aapt_package");
  }

}
