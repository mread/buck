package com.facebook.buck.groovy;

import com.facebook.buck.java.JUnitStep;
import com.facebook.buck.java.TestType;
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
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ZipFileTraversal;
import com.google.common.annotations.VisibleForTesting;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

public class GroovyTest extends GroovyLibrary implements TestRule {

  private final ImmutableList<String> vmArgs;
  private final ImmutableSet<Label> labels;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<BuildRule> sourceUnderTest;
  private CompiledClassFileFinder compiledClassFileFinder;

  protected GroovyTest(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      List<String> vmArgs,
      Set<Label> labels,
      Set<String> contacts,
      ImmutableSet<BuildRule> sourceUnderTest,
      Optional<Path> resourcesRoot
  ) {
    super(
        params,
        srcs,
        resources,
        /* exportDeps */ ImmutableSortedSet.<BuildRule>of(),
        /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
        resourcesRoot);
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.sourceUnderTest = Preconditions.checkNotNull(sourceUnderTest);
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return true;
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      TestSelectorList testSelectorList) {

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
        .addAll(getBootClasspathEntries(executionContext))
        .build();

    Step junit = new JUnitStep(
        classpathEntries,
        testClassNames,
        vmArgs,
        pathToTestOutput,
        tmpDirectory,
        executionContext.isCodeCoverageEnabled(),
        executionContext.isDebugEnabled(),
        executionContext.getBuckEventBus().getBuildId(),
        testSelectorList,
        isDryRun,
        TestType.JUNIT);
    steps.add(junit);

    return steps.build();
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
          if (!isUsingTestSelectors && !testResultFile.isFile()) {
            summaries.add(
                getTestClassFailedSummary(
                    testClass,
                    "test exited before generating results file"));
            // Not having a test result file at all (which only happens when we are using test
            // selectors) is interpreted as meaning a test didn't run at all, so we'll completely
            // ignore it.  This is another result of the fact that JUnit is the only thing that can
            // definitively say whether or not a class should be run.  It's not possible, for example,
            // to filter testClassNames here at the buck end.
          } else if (testResultFile.isFile()) {
            summaries.add(XmlTestResultParser.parse(testResultFile));
          }
        }

        return new TestResults(getBuildTarget(), summaries, contacts);
      }

    };
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
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return sourceUnderTest;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    List<String> pathsList = Lists.newArrayList();
    pathsList.add(getBuildTarget().getBaseNameWithSlash());
    pathsList.add(String.format("__groovy_test_%s_output__", getBuildTarget().getShortName()));

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
    Path base = BuildTargets.getBinPath(
        getBuildTarget(),
        "__groovy_test_%s_tmp__").toAbsolutePath();
    String subdir = BuckConstant.oneTimeTestSubdirectory;
    if (subdir != null && !subdir.isEmpty()) {
      base = base.resolve(subdir);
    }
    return base;
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

    CompiledClassFileFinder(GroovyTest rule, ExecutionContext context) {
      Path outputPath;
      Path relativeOutputPath = rule.getPathToOutputFile();
      if (relativeOutputPath != null) {
        outputPath = context.getProjectFilesystem().getAbsolutifier().apply(relativeOutputPath);
      } else {
        outputPath = null;
      }
      classNamesForSources = getClassNamesForSources(
          rule.getJavaSrcs(),
          outputPath,
          context.getProjectFilesystem());
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
     * @param sources     paths to .java source files that were passed to javac
     * @param jarFilePath jar where the generated .class files were written
     */
    @VisibleForTesting
    static Set<String> getClassNamesForSources(
        Set<SourcePath> sources,
        @Nullable Path jarFilePath,
        ProjectFilesystem projectFilesystem) {
      if (jarFilePath == null) {
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
        source = source.substring(0, source.length() - ".groovy".length());
        sourceClassNames.add(source);
      }

      final ImmutableSet.Builder<String> testClassNames = ImmutableSet.builder();
      File jarFile = projectFilesystem.getFileForRelativePath(jarFilePath);
      ZipFileTraversal traversal = new ZipFileTraversal(jarFile) {

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

      return testClassNames.build();
    }
  }

  /**
   * @param context That may be useful in producing the bootclasspath entries.
   */
  protected Set<Path> getBootClasspathEntries(ExecutionContext context) {
    return ImmutableSet.of();
  }

  /**
   * @return a test case result, named "main", signifying a failure of the entire test class.
   */
  private TestCaseSummary getTestClassFailedSummary(String testClass, String message) {
    return new TestCaseSummary(
        testClass,
        ImmutableList.of(
            new TestResultSummary(
                testClass,
                "main",
                ResultType.FAILURE,
                0L,
                message,
                "",
                "",
                "")));
  }

}

