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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

public class UninstallCommand extends UninstallSupportCommandRunner<UninstallCommandOptions> {
  public UninstallCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  UninstallCommandOptions createOptions(BuckConfig buckConfig) {
    return new UninstallCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(UninstallCommandOptions options) throws IOException {
    // Set the logger level based on the verbosity option.
    Verbosity verbosity = console.getVerbosity();
    Logging.setLoggingLevelForVerbosity(verbosity);

    // Make sure that only one build target is specified.
    if (options.getArguments().size() != 1) {
      getStdErr().println("Must specify exactly one android_binary() rule.");
      return 1;
    }

    // Get a parser.
    Parser parser = getParser();

    // Parse all of the build targets specified by the user.
    BuildTargetParser buildTargetParser = parser.getBuildTargetParser();
    String buildTargetName = options.getArgumentsFormattedAsBuildTargets().get(0);
    DependencyGraph dependencyGraph;
    BuildTarget buildTarget;
    try {
      buildTarget = buildTargetParser.parse(buildTargetName, ParseContext.fullyQualified());
      dependencyGraph = parser.parseBuildFilesForTargets(ImmutableList.of(buildTarget),
          options.getDefaultIncludes(),
          getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Find the android_binary() rule from the parse.
    BuildRule buildRule = dependencyGraph.findBuildRuleByTarget(buildTarget);
    if (!(buildRule instanceof InstallableApk)) {
      console.printBuildFailure(String.format(
          "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
          buildRule.getFullyQualifiedName(),
          buildRule.getType().getName()));
      return 1;
    }
    InstallableApk installableApk = (InstallableApk)buildRule;

    // We need this in case adb isn't already running.
    ExecutionContext context = createExecutionContext(options, dependencyGraph);

    // Find application package name from manifest and uninstall from matching devices.
    String appId = tryToExtractPackageNameFromManifest(installableApk,
        dependencyGraph);
    return uninstallApk(appId,
        options.adbOptions(),
        options.targetDeviceOptions(),
        options.uninstallOptions(),
        context,
        options.getBuckConfig()) ? 0 : 1;
  }

  @Override
  String getUsageIntro() {
    return "Specify an android_binary() rule whose APK should be uninstalled";
  }
}
