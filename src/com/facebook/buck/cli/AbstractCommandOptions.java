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

package com.facebook.buck.cli;

import com.facebook.buck.command.Build;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.Option;

import javax.annotation.Nullable;

public abstract class AbstractCommandOptions {

  @VisibleForTesting static final String HELP_LONG_ARG = "--help";

  /**
   * This value should never be read. {@link VerbosityParser} should be used instead.
   * args4j requires that all options that could be passed in are listed as fields, so we include
   * this field so that {@code --verbose} is universally available to all commands.
   */
  @Option(
      name = VerbosityParser.VERBOSE_LONG_ARG,
      aliases = { VerbosityParser.VERBOSE_SHORT_ARG },
      usage = "Specify a number between 1 and 10.")
  @SuppressWarnings("unused")
  private int verbosityLevel = -1;

  @Option(
      name = "--no-cache",
      usage = "Whether to ignore the [cache] declared in .buckconfig.")
  private boolean noCache = false;

  @Option(
      name = HELP_LONG_ARG,
      usage = "Prints the available options and exits.")
  private boolean help = false;

  private final BuckConfig buckConfig;

  @Nullable // Lazily loaded via getCommandLineBuildTargetNormalizer().
  private CommandLineBuildTargetNormalizer commandLineBuildTargetNormalizer;

  AbstractCommandOptions(BuckConfig buckConfig) {
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
  }

  /** @return {code true} if the {@code [cache]} in {@code .buckconfig} should be ignored. */
  public boolean isNoCache() {
    return noCache;
  }

  protected BuckConfig getBuckConfig() {
    return buckConfig;
  }

  public Iterable<String> getDefaultIncludes() {
    return this.buckConfig.getDefaultIncludes();
  }

  public boolean showHelp() {
    return help;
  }

  protected CommandLineBuildTargetNormalizer getCommandLineBuildTargetNormalizer() {
    if (commandLineBuildTargetNormalizer == null) {
      commandLineBuildTargetNormalizer = new CommandLineBuildTargetNormalizer(buckConfig);
    }
    return commandLineBuildTargetNormalizer;
  }

  protected Optional<AndroidPlatformTarget> findAndroidPlatformTarget(
      AndroidDirectoryResolver androidDirectoryResolver,
      DependencyGraph dependencyGraph,
      BuckEventBus eventBus) {
    return Build.findAndroidPlatformTarget(
        dependencyGraph,
        androidDirectoryResolver,
        eventBus);
  }
}
