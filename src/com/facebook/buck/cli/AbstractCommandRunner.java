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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import javax.annotation.Nullable;

abstract class AbstractCommandRunner<T extends AbstractCommandOptions> implements CommandRunner {

  private final CommandRunnerParams commandRunnerParams;
  protected final Console console;
  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final ArtifactCacheFactory artifactCacheFactory;
  private final Parser parser;
  private final BuckEventBus eventBus;
  private final Platform platform;
  private final AndroidDirectoryResolver androidDirectoryResolver;

  /** This is constructed lazily. */
  @Nullable private T options;

  /** This is constructed lazily. */
  @Nullable private volatile ArtifactCache artifactCache;

  protected AbstractCommandRunner(CommandRunnerParams params) {
    this.commandRunnerParams = Preconditions.checkNotNull(params);
    this.console = Preconditions.checkNotNull(params.getConsole());
    this.projectFilesystem = Preconditions.checkNotNull(params.getProjectFilesystem());
    this.buildRuleTypes = Preconditions.checkNotNull(params.getBuildRuleTypes());
    this.artifactCacheFactory = Preconditions.checkNotNull(params.getArtifactCacheFactory());
    this.parser = Preconditions.checkNotNull(params.getParser());
    this.eventBus = Preconditions.checkNotNull(params.getBuckEventBus());
    this.platform = Preconditions.checkNotNull(params.getPlatform());
    this.androidDirectoryResolver =
        Preconditions.checkNotNull(params.getAndroidDirectoryResolver());
  }

  abstract T createOptions(BuckConfig buckConfig);

  private ParserAndOptions<T> createParser(BuckConfig buckConfig) {
    T options = createOptions(buckConfig);
    return new ParserAndOptions<>(options);
  }

  @Override
  public final int runCommand(BuckConfig buckConfig, List<String> args) throws IOException {
    ParserAndOptions<T> parserAndOptions = createParser(buckConfig);
    T options = parserAndOptions.options;
    CmdLineParser parser = parserAndOptions.parser;

    boolean hasValidOptions = false;
    try {
      parser.parseArgument(args);
      hasValidOptions = true;
    } catch (CmdLineException e) {
      console.getStdErr().println(e.getMessage());
    }

    if (hasValidOptions && !options.showHelp()) {
      return runCommandWithOptions(options);
    } else {
      printUsage(parser);
      return 1;
    }
  }

  public final void printUsage(CmdLineParser parser) {
    String intro = getUsageIntro();
    if (intro != null) {
      getStdErr().println(intro);
    }
    parser.printUsage(getStdErr());
  }

  /**
   * @return the exit code this process should exit with
   */
  public final synchronized int runCommandWithOptions(T options) throws IOException {
    this.options = options;
    return runCommandWithOptionsInternal(options);
  }

  /**
   * Invoked by {@link #runCommandWithOptions(AbstractCommandOptions)} after {@code #options} has
   * been set.
   * @return the exit code this process should exit with
   */
  abstract int runCommandWithOptionsInternal(T options) throws IOException;

  /**
   * @return may be null
   */
  abstract String getUsageIntro();

  /**
   * Sometimes a {@link CommandRunner} needs to create another {@link CommandRunner}, so it should
   * use this method so it can reuse the constructor parameter that it received.
   */
  protected CommandRunnerParams getCommandRunnerParams() {
    return commandRunnerParams;
  }

  public AndroidDirectoryResolver getAndroidDirectoryResolver() {
    return androidDirectoryResolver;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public KnownBuildRuleTypes getBuildRuleTypes() {
    return buildRuleTypes;
  }

  /**
   * @return A list of {@link BuildTarget}s for the input buildTargetNames.
   */
  protected ImmutableList<BuildTarget> getBuildTargets(List<String> buildTargetNames)
      throws NoSuchBuildTargetException, IOException {
    Preconditions.checkNotNull(buildTargetNames);
    ImmutableList.Builder<BuildTarget> buildTargets = ImmutableList.builder();

    // Parse all of the build targets specified by the user.
    BuildTargetParser buildTargetParser = getParser().getBuildTargetParser();

    for (String buildTargetName : buildTargetNames) {
      buildTargets.add(buildTargetParser.parse(buildTargetName, ParseContext.fullyQualified()));
    }

    return buildTargets.build();
  }

  public ArtifactCache getArtifactCache() {
    // Lazily construct the ArtifactCache, as not all commands (like `buck clean`) need it.
    if (artifactCache == null) {
      synchronized (this) {
        if (artifactCache == null) {
          Preconditions.checkNotNull(options,
              "getArtifactCache() should not be invoked before runCommand().");
          artifactCache = artifactCacheFactory.newInstance(options);
        }
      }
    }
    return artifactCache;
  }

  protected PrintStream getStdOut() {
    return console.getStdOut();
  }

  protected PrintStream getStdErr() {
    return console.getStdErr();
  }

  protected BuckEventBus getBuckEventBus() {
    return eventBus;
  }

  /**
   * @return Returns a potentially cached Parser for this command.
   */
  public Parser getParser() {
    return parser;
  }

  private static class ParserAndOptions<T> {
    private final T options;
    private final CmdLineParser parser;

    private ParserAndOptions(T options) {
      this.options = options;
      this.parser = new CmdLineParserAdditionalOptions(options);
    }
  }

  protected ExecutionContext createExecutionContext(
      T options,
      DependencyGraph dependencyGraph) {
    return ExecutionContext.builder()
        .setProjectFilesystem(getProjectFilesystem())
        .setConsole(console)
        .setAndroidPlatformTarget(
            options.findAndroidPlatformTarget(androidDirectoryResolver,
                dependencyGraph,
                getBuckEventBus()))
        .setEventBus(eventBus)
        .setPlatform(platform)
        .build();
  }

}
