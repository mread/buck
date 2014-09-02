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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ZipFileTraversal;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class JavaTest extends DefaultJavaLibrary implements TestRule {

  private final ImmutableList<String> vmArgs;

  private CompiledClassFileFinder compiledClassFileFinder;

  private final ImmutableSet<Label> labels;

  private final ImmutableSet<String> contacts;

  private ImmutableSet<BuildRule> sourceUnderTest;

  private final ImmutableSet<Path> additionalClasspathEntries;

  protected JavaTest(
      BuildRuleParams params,
      Set<SourcePath> srcs,
      Set<SourcePath> resources,
      Set<Label> labels,
      Set<String> contacts,
      Optional<Path> proguardConfig,
      ImmutableSet<Path> addtionalClasspathEntries,
      JavacOptions javacOptions,
      List<String> vmArgs,
      ImmutableSet<BuildRule> sourceUnderTest,
      Optional<Path> resourcesRoot) {
    super(
        params,
        srcs,
        resources,
        proguardConfig,
        ImmutableList.<String>of(),
        /* exportDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        addtionalClasspathEntries,
        javacOptions,
        resourcesRoot);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.sourceUnderTest = Preconditions.checkNotNull(sourceUnderTest);
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.additionalClasspathEntries = Preconditions.checkNotNull(addtionalClasspathEntries);
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    ImmutableSortedSet<? extends BuildRule> srcUnderTest = ImmutableSortedSet.copyOf(
        sourceUnderTest);
    super.appendDetailsToRuleKey(builder)
        .setReflectively("vmArgs", vmArgs)
        .setReflectively("sourceUnderTest", srcUnderTest);
    return builder;
  }

  /**
   * @return A set of rules that this test rule will be testing.
   */
  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return sourceUnderTest;
  }

  public ImmutableList<String> getVmArgs() {
    return vmArgs;
  }

  /**
   * @param context That may be useful in producing the bootclasspath entries.
   */
  protected Set<Path> getBootClasspathEntries(ExecutionContext context) {
    return ImmutableSet.of();
  }

  /**
   * Runs the tests specified by the "srcs" of this class. If this rule transitively depends on
   * other {@code java_test()} rules, then they will be run separately.
   */
  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      TestSelectorList testSelectorList) {
    // If no classes were generated, then this is probably a java_test() that declares a number of
    // other java_test() rules as deps, functioning as a test suite. In this case, simply return an
    // empty list of commands.
    Set<String> testClassNames = getClassNamesForSources(executionContext);
    if (testClassNames.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    Path tmpDirectory = getPathToTmpDirectory();
    steps.add(new MakeCleanDirectoryStep(pathToTestOutput));
    steps.add(new MakeCleanDirectoryStep(tmpDirectory));

    ImmutableSet<Path> classpathEntries = ImmutableSet.<Path>builder()
        .addAll(getTransitiveClasspathEntries().values())
        .addAll(additionalClasspathEntries)
        .addAll(getBootClasspathEntries(executionContext))
        .build();

    Step junit = new JUnitStep(
        classpathEntries,
        testClassNames,
        amendVmArgs(vmArgs, executionContext.getTargetDeviceOptional()),
        pathToTestOutput,
        getBuildTarget().getBasePath(),
        tmpDirectory,
        executionContext.isCodeCoverageEnabled(),
        executionContext.isDebugEnabled(),
        executionContext.getBuckEventBus().getBuildId(),
        testSelectorList,
        isDryRun);
    steps.add(junit);

    return steps.build();
  }

  @VisibleForTesting
  List<String> amendVmArgs(List<String> existingVmArgs, Optional<TargetDevice> targetDevice) {
    ImmutableList.Builder<String> vmArgs = ImmutableList.builder();
    vmArgs.addAll(existingVmArgs);
    onAmendVmArgs(vmArgs, targetDevice);
    return vmArgs.build();
  }

  /**
   * Override this method if you need to amend vm args. Subclasses are required
   * to call super.onAmendVmArgs(...).
   */
  protected void onAmendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder,
      Optional<TargetDevice> targetDevice) {
    if (!targetDevice.isPresent()) {
      return;
    }

    TargetDevice device = targetDevice.get();
    if (device.isEmulator()) {
      vmArgsBuilder.add("-Dbuck.device=emulator");
    } else {
      vmArgsBuilder.add("-Dbuck.device=device");
    }
    if (device.hasIdentifier()) {
      vmArgsBuilder.add("-Dbuck.device.id=" + device.getIdentifier());
    }
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    // It is possible that this rule was not responsible for running any tests because all tests
    // were run by its deps. In this case, return an empty TestResults.
    Set<String> testClassNames = getClassNamesForSources(executionContext);
    if (testClassNames.isEmpty()) {
      return true;
    }

    File outputDirectory = executionContext.getProjectFilesystem().getFileForRelativePath(
        getPathToTestOutputDirectory());
    for (String testClass : testClassNames) {
      // We never use cached results when using test selectors, so there's no need to incorporate
      // the .test_selectors suffix here if we are using selectors.
      File testResultFile = new File(outputDirectory, testClass + ".xml");
      if (!testResultFile.isFile()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    List<String> pathsList = Lists.newArrayList();
    pathsList.add(getBuildTarget().getBaseNameWithSlash());
    pathsList.add(String.format("__java_test_%s_output__", getBuildTarget().getShortName()));

    // Putting the one-time test-sub-directory below the usual directory has the nice property that
    // doing a test run without "--one-time-output" will tidy up all the old one-time directories!
    String subdir = BuckConstant.oneTimeTestSubdirectory;
    if (subdir != null && !subdir.isEmpty()) {
      pathsList.add(subdir);
    }

    String[] pathsArray = pathsList.toArray(new String[pathsList.size()]);
    return Paths.get(BuckConstant.GEN_DIR, pathsArray);
  }

  private Path getPathToTmpDirectory() {
    Path base = BuildTargets.getBinPath(getBuildTarget(), "__java_test_%s_tmp__").toAbsolutePath();
    String subdir = BuckConstant.oneTimeTestSubdirectory;
    if (subdir != null && !subdir.isEmpty()) {
      base = base.resolve(subdir);
    }
    return base;
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext context,
      final boolean isUsingTestSelectors,
      final boolean isDryRun) {
    final ImmutableSet<String> contacts = getContacts();
    return new Callable<TestResults>() {

      @Override
      public TestResults call() throws Exception {
        // It is possible that this rule was not responsible for running any tests because all tests
        // were run by its deps. In this case, return an empty TestResults.
        Set<String> testClassNames = getClassNamesForSources(context);
        if (testClassNames.isEmpty()) {
          return new TestResults(getBuildTarget(), ImmutableList.<TestCaseSummary>of(), contacts);
        }

        List<TestCaseSummary> summaries = Lists.newArrayListWithCapacity(testClassNames.size());
        ProjectFilesystem filesystem = context.getProjectFilesystem();
        for (String testClass : testClassNames) {
          String testSelectorSuffix = "";
          if (isUsingTestSelectors) {
            testSelectorSuffix += ".test_selectors";
          }
          if (isDryRun) {
            testSelectorSuffix += ".dry_run";
          }
          String path = String.format("%s%s.xml", testClass, testSelectorSuffix);
          File testResultFile = filesystem.getFileForRelativePath(
              getPathToTestOutputDirectory().resolve(path));
          // Not having a test result file at all (which only happens when we are using test
          // selectors) is interpreted as meaning a test didn't run at all, so we'll completely
          // ignore it.  This is another result of the fact that JUnit is the only thing that can
          // definitively say whether or not a class should be run.  It's not possible, for example,
          // to filter testClassNames here at the buck end.
          if (!isUsingTestSelectors || testResultFile.isFile()) {
            TestCaseSummary summary = XmlTestResultParser.parse(testResultFile);
            summaries.add(summary);
          }
        }

        return new TestResults(getBuildTarget(), summaries, contacts);
      }

    };
  }

  private Set<String> getClassNamesForSources(ExecutionContext context) {
    if (compiledClassFileFinder == null) {
      compiledClassFileFinder = new CompiledClassFileFinder(this, context);
    }
    return compiledClassFileFinder.getClassNamesForSources();
  }

  @VisibleForTesting
  static class CompiledClassFileFinder {

    private final Set<String> classNamesForSources;

    CompiledClassFileFinder(JavaTest rule, ExecutionContext context) {
      Path relativeOutputPath = rule.getPathToOutputFile();
      List<Path> paths = new ArrayList<>();
      final Function<Path, Path> absolutifier = context.getProjectFilesystem().getAbsolutifier();
      if (relativeOutputPath != null) {
        paths.add(absolutifier.apply(relativeOutputPath));
      }
      for (BuildRule dependency : rule.getDeps()) {
        final Path pathToOutputFile = dependency.getPathToOutputFile();
        if (pathToOutputFile != null && pathToOutputFile.endsWith(".jar")) {
          paths.add(absolutifier.apply(pathToOutputFile));
        }
      }
      classNamesForSources = getClassNamesForSources(
          rule.getJavaSrcs(),
          paths.toArray(new Path[paths.size()]));
    }

    public Set<String> getClassNamesForSources() {
      return classNamesForSources;
    }

    /**
     * When a collection of .java files is compiled into a directory, that directory will have a
     * subfolder structure that matches the package structure of the input .java files. In general,
     * the .java files will be 1:1 with the .class files with two notable exceptions:
     * (1) There will be an additional .class file for each inner/anonymous class generated. These
     * types of classes are easy to identify because they will contain a '$' in the name.
     * (2) A .java file that defines multiple top-level classes (yes, this can exist:
     * http://stackoverflow.com/questions/2336692/java-multiple-class-declarations-in-one-file)
     * will generate multiple .class files that do not have '$' in the name.
     * In this method, we perform a strict check for (1) and use a heuristic for (2). It is possible
     * to filter out the type (2) situation with a stricter check that aligns the package
     * directories of the .java files and the .class files, but it is a pain to implement.
     * If this heuristic turns out to be insufficient in practice, then we can fix it.
     *
     * @param sources  paths to .java source files that were passed to javac
     * @param jarFiles jars where the generated .class files were written
     */
    @VisibleForTesting
    static Set<String> getClassNamesForSources(Set<SourcePath> sources, Path... jarFiles) {
      if (jarFiles == null || jarFiles.length == 0) {
        return ImmutableSet.of();
      }

      final Set<String> sourceClassNames = Sets.newHashSetWithExpectedSize(sources.size());
      for (SourcePath sourcePath : sources) {
        Path path = sourcePath.resolve();
        String source = path.toString();
        int lastSlashIndex = source.lastIndexOf('/');
        if (lastSlashIndex >= 0) {
          source = source.substring(lastSlashIndex + 1);
        }
        source = removeSuffix(source);
        sourceClassNames.add(source);
      }

      final ImmutableSet.Builder<String> testClassNames = ImmutableSet.builder();
      for (Path jarFile : jarFiles) {
        ZipFileTraversal traversal = new ZipFileTraversal(jarFile.toFile()) {

          @Override
          public void visit(ZipFile zipFile, ZipEntry zipEntry) {
            final String name = new File(zipEntry.getName()).getName();

            // Ignore non-.class files.
            if (!name.endsWith(".class")) {
              return;
            }

            // As a heuristic for case (2) as described in the Javadoc, make sure the name of the
            // .class file matches the name of a .java file.
            String nameWithoutDotClass = name.substring(0, name.length() - ".class".length());
            if (!sourceClassNames.contains(nameWithoutDotClass)) {
              return;
            }

            // Make sure it is a .class file that corresponds to a top-level .class file and not an
            // inner class.
            if (!name.contains("$")) {
              String fullyQualifiedNameWithDotClassSuffix = zipEntry.getName().replace('/', '.');
              String className = fullyQualifiedNameWithDotClassSuffix
                  .substring(0, fullyQualifiedNameWithDotClassSuffix.length() - ".class".length());
              testClassNames.add(className);
            }
          }
        };
        try {
          traversal.traverse();
        } catch (IOException e) {
          // There's nothing sane to do here. The jar file really should exist.
          throw Throwables.propagate(e);
        }
      }

      return testClassNames.build();
    }

    private static String removeSuffix(String source) {
      return source.substring(0, source.lastIndexOf('.'));
    }
  }
}
