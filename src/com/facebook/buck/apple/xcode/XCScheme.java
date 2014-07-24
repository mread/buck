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

package com.facebook.buck.apple.xcode;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.EnumSet;
import java.util.List;

public class XCScheme {
  private String name;
  private BuildAction buildAction;
  private TestAction testAction;
  private LaunchAction launchAction;
  private ProfileAction profileAction;
  private AnalyzeAction analyzeAction;
  private ArchiveAction archiveAction;

  public XCScheme(
      String name,
      BuildAction buildAction,
      TestAction testAction,
      LaunchAction launchAction,
      ProfileAction profileAction,
      AnalyzeAction analyzeAction,
      ArchiveAction archiveAction) {
    this.name = name;
    this.buildAction = buildAction;
    this.testAction = testAction;
    this.launchAction = launchAction;
    this.profileAction = profileAction;
    this.analyzeAction = Preconditions.checkNotNull(analyzeAction);
    this.archiveAction = Preconditions.checkNotNull(archiveAction);
  }

  public String getName() {
    return name;
  }

  public BuildAction getBuildAction() {
    return buildAction;
  }

  public TestAction getTestAction() {
    return testAction;
  }

  public LaunchAction getLaunchAction() {
    return launchAction;
  }

  public ProfileAction getProfileAction() {
    return profileAction;
  }

  public AnalyzeAction getAnalyzeAction() {
    return analyzeAction;
  }

  public ArchiveAction getArchiveAction() {
    return archiveAction;
  }

  public static class BuildableReference {
    private String containerRelativePath;
    private String blueprintIdentifier;
    public final String buildableName;
    public final String blueprintName;

    public BuildableReference(
        String containerRelativePath,
        String blueprintIdentifier,
        String buildableName,
        String blueprintName) {
      this.containerRelativePath = containerRelativePath;
      this.blueprintIdentifier = blueprintIdentifier;
      this.buildableName = Preconditions.checkNotNull(buildableName);
      this.blueprintName = Preconditions.checkNotNull(blueprintName);
    }

    public String getContainerRelativePath() {
      return containerRelativePath;
    }

    public String getBlueprintIdentifier() {
      return blueprintIdentifier;
    }

    public String getBuildableName() {
      return buildableName;
    }

    public String getBlueprintName() {
      return blueprintName;
    }
  }

  public static class BuildAction {
    private List<BuildActionEntry> buildActionEntries;

    public BuildAction() {
      buildActionEntries = Lists.newArrayList();
    }

    public void addBuildAction(BuildActionEntry entry) {
      this.buildActionEntries.add(entry);
    }

    public List<BuildActionEntry> getBuildActionEntries() {
      return buildActionEntries;
    }
  }

  public static class BuildActionEntry {
    enum BuildFor {
      RUNNING,
      TESTING,
      PROFILING,
      ARCHIVING,
      ANALYZING;

      public static final EnumSet<BuildFor> DEFAULT = EnumSet.allOf(BuildFor.class);
      public static final EnumSet<BuildFor> TEST_ONLY = EnumSet.of(TESTING, ANALYZING);
    }

    private BuildableReference buildableReference;

    private final EnumSet<BuildFor> buildFor;

    public BuildActionEntry(
        BuildableReference buildableReference,
        EnumSet<BuildFor> buildFor) {
      this.buildableReference = buildableReference;
      this.buildFor = Preconditions.checkNotNull(buildFor);
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public EnumSet<BuildFor> getBuildFor() {
      return buildFor;
    }
  }

  public static class LaunchAction {
    BuildableReference buildableReference;
    private final String buildConfiguration;

    public LaunchAction(BuildableReference buildableReference, String buildConfiguration) {
      this.buildableReference = buildableReference;
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }

  public static class ProfileAction {
    BuildableReference buildableReference;
    private final String buildConfiguration;

    public ProfileAction(BuildableReference buildableReference, String buildConfiguration) {
      this.buildableReference = buildableReference;
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }

  public static class TestAction {
    List<TestableReference> testables;
    private final String buildConfiguration;

    public TestAction(String buildConfiguration) {
      this.testables = Lists.newArrayList();
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public void addTestableReference(TestableReference testable) {
      this.testables.add(testable);
    }

    public List<TestableReference> getTestables() {
      return testables;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }

  public static class TestableReference {
    private BuildableReference buildableReference;

    public TestableReference(BuildableReference buildableReference) {
      this.buildableReference = buildableReference;
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }
  }

  public static class AnalyzeAction {
    public final String buildConfiguration;

    public AnalyzeAction(String buildConfiguration) {
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }

  public static class ArchiveAction {
    public final String buildConfiguration;

    public ArchiveAction(String buildConfiguration) {
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }
}
