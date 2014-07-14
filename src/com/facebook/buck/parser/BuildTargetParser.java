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

package com.facebook.buck.parser;

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class BuildTargetParser {

  private static final String BUILD_RULE_PREFIX = "//";
  private static final String BUILD_RULE_SEPARATOR = ":";
  private static final String REPOSITORY_STARTER = "@";
  private static final Splitter BUILD_RULE_SEPARATOR_SPLITTER = Splitter.on(BUILD_RULE_SEPARATOR);
  private static final List<String> INVALID_BUILD_RULE_SUBSTRINGS = ImmutableList.of("..", "./");

  private final ProjectFilesystem projectFilesystem;

  public BuildTargetParser(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
  }

  /**
   * @param buildTargetName either a fully-qualified name or relative to the {@link ParseContext}.
   *     For example, inside {@code first-party/orca/orcaapp/BUILD}, which can be obtained by
   *     calling {@code ParseContext.forBaseName("first-party/orca/orcaapp")},
   *     {@code //first-party/orca/orcaapp:assets} and {@code :assets} refer to the same target.
   *     However, from the command line the context is obtained by calling
   *     {@link ParseContext#fullyQualified()} and relative names are not recognized.
   * @param parseContext how targets should be interpreted, such in the context of a specific build
   *     file or only as fully-qualified names (as is the case for targets from the command line).
   */
  public BuildTarget parse(String buildTargetName, ParseContext parseContext)
      throws NoSuchBuildTargetException {
    Preconditions.checkNotNull(buildTargetName);
    Preconditions.checkNotNull(parseContext);

    for (String invalidSubstring : INVALID_BUILD_RULE_SUBSTRINGS) {
      if (buildTargetName.contains(invalidSubstring)) {
        throw new BuildTargetParseException(
            String.format("%s cannot contain %s", buildTargetName, invalidSubstring));
      }
    }

    if (buildTargetName.endsWith(BUILD_RULE_SEPARATOR) &&
        parseContext.getType() != ParseContext.Type.VISIBILITY) {
      throw new BuildTargetParseException(
          String.format("%s cannot end with a colon", buildTargetName));
    }

    Optional<String> repoName = Optional.absent();
    String targetAfterRepo = buildTargetName;
    if (buildTargetName.startsWith(REPOSITORY_STARTER)) {
      if (!buildTargetName.contains(BUILD_RULE_PREFIX)) {
        throw new BuildTargetParseException(
            String.format(
                "Cross-repo paths must contain %s (found %s)",
                BUILD_RULE_PREFIX,
                buildTargetName));
      }
      int slashIndex = buildTargetName.indexOf(BUILD_RULE_PREFIX);
      repoName = Optional.of(buildTargetName.substring(REPOSITORY_STARTER.length(), slashIndex));
      targetAfterRepo = buildTargetName.substring(slashIndex);
    }

    if (repoName.isPresent() && repoName.get().isEmpty()) {
      throw new BuildTargetParseException("Repo name must not be empty.");
    }

    List<String> parts = BUILD_RULE_SEPARATOR_SPLITTER.splitToList(targetAfterRepo);
    if (parts.size() != 2) {
      throw new BuildTargetParseException(String.format(
          "%s must contain exactly one colon (found %d)", buildTargetName, parts.size() - 1));
    }

    String baseName = parts.get(0).isEmpty() ? parseContext.getBaseName() : parts.get(0);
    String shortName = parts.get(1);

    String fullyQualifiedName = String.format("%s:%s", baseName, shortName);
    if (!fullyQualifiedName.startsWith(BUILD_RULE_PREFIX)) {
      throw new BuildTargetParseException(
          String.format("%s must start with %s", fullyQualifiedName, BUILD_RULE_PREFIX));
    }

    // Make sure the directory that contains the build file exists.
    Path buildFileDirectory = Paths.get(baseName.substring(BUILD_RULE_PREFIX.length()));
    Path buildFilePath = buildFileDirectory.resolve(BUILD_RULES_FILE_NAME);
    if (!projectFilesystem.exists(buildFileDirectory)) {
      if (parseContext.getType() == ParseContext.Type.BUILD_FILE &&
          baseName.equals(parseContext.getBaseName())) {
        throw new BuildTargetParseException(String.format(
            "Internal error: Parsing in the context of %s, but %s does not exist",
            buildFilePath,
            buildFileDirectory));
      } else {
        throw NoSuchBuildTargetException.createForMissingDirectory(buildFileDirectory,
            buildTargetName,
            parseContext);
      }
    }

    // Make sure the build file exists.
    if (!projectFilesystem.exists(buildFilePath)) {
      if (parseContext.getType() == ParseContext.Type.BUILD_FILE &&
          baseName.equals(parseContext.getBaseName())) {
        throw new BuildTargetParseException(String.format(
            "Internal error: Parsing in the context of %s, but %s does not exist",
            buildFilePath,
            buildFilePath));
      } else {
        throw NoSuchBuildTargetException.createForMissingBuildFile(buildFilePath,
            buildTargetName,
            parseContext);
      }
    }

    BuildTarget.Builder builder = BuildTarget.builder(baseName, shortName);
    if (repoName.isPresent()) {
      builder.setRepository(repoName.get()).build();
    }
    return builder.build();
  }
}
