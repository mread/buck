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

package com.facebook.buck.testutil.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.Main;
import com.facebook.buck.cli.TestCommand;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.MoreFiles;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;

/**
 * {@link ProjectWorkspace} is a directory that contains a Buck project, complete with build files.
 * <p>
 * When {@link #setUp()} is invoked, the project files are cloned from a directory of testdata into
 * a tmp directory according to the following rule:
 * <ul>
 *   <li>Files with the {@code .expected} extension will not be copied.
 * </ul>
 * After {@link #setUp()} is invoked, the test should invoke Buck in that directory. As this is an
 * integration test, we expect that files will be written as a result of invoking Buck.
 * <p>
 * After Buck has been run, invoke {@link #verify()} to verify that Buck wrote the correct files.
 * For each file in the testdata directory with the {@code .expected} extension, {@link #verify()}
 * will check that a file with the same relative path (but without the {@code .expected} extension)
 * exists in the tmp directory. If not, {@link org.junit.Assert#fail()} will be invoked.
 */
public class ProjectWorkspace {

  private static final String EXPECTED_SUFFIX = ".expected";

  private static final String PATH_TO_BUILD_LOG = "buck-out/bin/build.log";

  private static final Function<Path, Path> BUILD_FILE_RENAME = new Function<Path, Path>() {
    @Override
    @Nullable
    public Path apply(Path path) {
      String fileName = path.getFileName().toString();
      if (fileName.endsWith(EXPECTED_SUFFIX)) {
        return null;
      } else {
        return path;
      }
    }
  };

  private boolean isSetUp = false;
  private final Path templatePath;
  private final File destDir;
  private final Path destPath;

  /**
   * @param templateDir The directory that contains the template version of the project.
   * @param temporaryFolder The directory where the clone of the template directory should be
   *     written. By requiring a {@link TemporaryFolder} rather than a {@link File}, we can ensure
   *     that JUnit will clean up the test correctly.
   */
  public ProjectWorkspace(File templateDir, DebuggableTemporaryFolder temporaryFolder) {
    Preconditions.checkNotNull(templateDir);
    Preconditions.checkNotNull(temporaryFolder);
    this.templatePath = templateDir.toPath();
    this.destDir = temporaryFolder.getRoot();
    this.destPath = destDir.toPath();
  }

  /**
   * This will copy the template directory, renaming files named {@code BUCK.test} to {@code BUCK}
   * in the process. Files whose names end in {@code .expected} will not be copied.
   */
  public void setUp() throws IOException {
    DefaultKnownBuildRuleTypes.resetInstance();

    MoreFiles.copyRecursively(templatePath, destPath, BUILD_FILE_RENAME);

    if (Platform.detect() == Platform.WINDOWS) {
      // Hack for symlinks on Windows.
      SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          // On Windows, symbolic links from git repository are checked out as normal files
          // containing a one-line path. In order to distinguish them, paths are read and pointed
          // files are trued to locate. Once the pointed file is found, it will be copied to target.
          // On NTFS length of path must be greater than 0 and less than 4096.
          if (attrs.size() > 0 && attrs.size() <= 4096) {
            File file = path.toFile();
            String linkTo = Files.toString(file, Charsets.UTF_8);
            File linkToFile = new File(templatePath.toFile(), linkTo);
            if (linkToFile.isFile()) {
              java.nio.file.Files.copy(
                  linkToFile.toPath(), path, StandardCopyOption.REPLACE_EXISTING);
            } else if (linkToFile.isDirectory()) {
              if (!file.delete()) {
                throw new IOException();
              }
              MoreFiles.copyRecursively(linkToFile.toPath(), path);
            }
          }
          return FileVisitResult.CONTINUE;
        }
      };
      java.nio.file.Files.walkFileTree(destPath, copyDirVisitor);
    }
    isSetUp = true;
  }

  public ProcessResult runBuckBuild(String... args) throws IOException {
    String[] totalArgs = new String[args.length + 1];
    totalArgs[0] = "build";
    System.arraycopy(args, 0, totalArgs, 1, args.length);
    return runBuckCommand(totalArgs);
  }

  public File buildAndReturnOutput(String target) throws IOException {
    // Build the java_library.
    ProjectWorkspace.ProcessResult buildResult = runBuckBuild(target.toString());
    buildResult.assertSuccess();

    // Use `buck targets` to find the output JAR file.
    // TODO(jacko): This is going to overwrite the build.log. Maybe stash that and return it?
    ProjectWorkspace.ProcessResult outputFileResult = runBuckCommand(
        "targets",
        "--show_output",
        target.toString());
    outputFileResult.assertSuccess();
    String pathToGeneratedJarFile = outputFileResult.getStdout().split(" ")[1].trim();
    return getFile(pathToGeneratedJarFile);
  }

  public ProcessExecutor.Result runJar(File jar, String... args)
      throws IOException, InterruptedException {
    List<String> command = ImmutableList.<String>builder()
        .add("java")
        .add("-jar")
        .add(jar.toString())
        .addAll(ImmutableList.copyOf(args))
        .build();
    String[] commandArray = command.toArray(new String[command.size()]);
    Process process = Runtime.getRuntime().exec(commandArray);
    ProcessExecutor executor = new ProcessExecutor(new TestConsole());
    return executor.execute(process);
  }

  /**
   * Runs Buck with the specified list of command-line arguments.
   * @param args to pass to {@code buck}, so that could be {@code ["build", "//path/to:target"]},
   *   {@code ["project"]}, etc.
   * @return the result of running Buck, which includes the exit code, stdout, and stderr.
   */
  public ProcessResult runBuckCommand(String... args)
      throws IOException {
    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    Main main = new Main(stdout, stderr);
    int exitCode = 0;
    try {
      exitCode = main.runMainWithExitCode(destDir, Optional.<NGContext>absent(), args);
    } catch (InterruptedException e) {
      e.printStackTrace();
      Thread.currentThread().interrupt();
      exitCode = Main.FAIL_EXIT_CODE;
    }

    return new ProcessResult(exitCode,
        stdout.getContentsAsString(Charsets.UTF_8),
        stderr.getContentsAsString(Charsets.UTF_8));
  }

  public ProcessResult runBuckdCommand(String... args) throws IOException {
    return runBuckdCommand(ImmutableMap.copyOf(System.getenv()), args);
  }

  public ProcessResult runBuckdCommand(ImmutableMap<String, String> environment, String... args)
      throws IOException {

    assertTrue("setUp() must be run before this method is invoked", isSetUp);
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    NGContext context = new TestContext(environment);

    Main main = new Main(stdout, stderr);
    int exitCode = 0;
    try {
      exitCode = main.runMainWithExitCode(destDir, Optional.<NGContext>of(context), args);
    } catch (InterruptedException e) {
      e.printStackTrace();
      Thread.currentThread().interrupt();
      exitCode = Main.FAIL_EXIT_CODE;
    }

    return new ProcessResult(exitCode,
        stdout.getContentsAsString(Charsets.UTF_8),
        stderr.getContentsAsString(Charsets.UTF_8));

  }

  /**
   * @return the {@link File} that corresponds to the {@code pathRelativeToProjectRoot}.
   */
  public File getFile(String pathRelativeToProjectRoot) {
    return new File(destDir, pathRelativeToProjectRoot);
  }

  public String getFileContents(String pathRelativeToProjectRoot) throws IOException {
    return Files.toString(getFile(pathRelativeToProjectRoot), Charsets.UTF_8);
  }

  public void enableDirCache() throws IOException {
    writeContentsToPath("[cache]\n  mode = dir", ".buckconfig.local");
  }

  public void copyFile(String source, String dest) throws IOException {
    Files.copy(getFile(source), getFile(dest));
  }

  public void replaceFileContents(String pathRelativeToProjectRoot,
      String target,
      String replacement) throws IOException {
    String fileContents = getFileContents(pathRelativeToProjectRoot);
    fileContents = fileContents.replace(target, replacement);
    writeContentsToPath(fileContents, pathRelativeToProjectRoot);
  }

  public void writeContentsToPath(String contents, String pathRelativeToProjectRoot)
      throws IOException {
    Files.write(contents.getBytes(Charsets.UTF_8), getFile(pathRelativeToProjectRoot));
  }

  /**
   * @return the specified path resolved against the root of this workspace.
   */
  public Path resolve(Path pathRelativeToWorkspaceRoot) {
    return destPath.resolve(pathRelativeToWorkspaceRoot);
  }

  public void resetBuildLogFile() throws IOException {
    writeContentsToPath("", PATH_TO_BUILD_LOG);
  }

  public BuckBuildLog getBuildLog() throws IOException {
    return BuckBuildLog.fromLogContents(
        Files.readLines(getFile(PATH_TO_BUILD_LOG), Charsets.UTF_8));
  }

  /** The result of running {@code buck} from the command line. */
  public static class ProcessResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ProcessResult(int exitCode, String stdout, String stderr) {
      this.exitCode = exitCode;
      this.stdout = Preconditions.checkNotNull(stdout);
      this.stderr = Preconditions.checkNotNull(stderr);
    }

    /**
     * Returns the exit code from the process.
     * <p>
     * Currently, this method is private because, in practice, any time a client might want to use
     * it, it is more appropriate to use {@link #assertSuccess()} or
     * {@link #assertFailure()} instead. If a valid use case arises, then we should make this
     * getter public.
     */
    private int getExitCode() {
      return exitCode;
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
    }

    public void assertSuccess() {
      assertExitCode(null, 0);
    }

    public void assertSuccess(String message) {
      assertExitCode(message, 0);
    }

    public void assertFailure() {
      assertExitCode(null, Main.FAIL_EXIT_CODE);
    }

    public void assertTestFailure() {
      assertExitCode(null, TestCommand.TEST_FAILURES_EXIT_CODE);
    }

    public void assertTestFailure(String message) {
      assertExitCode(message, TestCommand.TEST_FAILURES_EXIT_CODE);
    }

    public void assertFailure(String message) {
      assertExitCode(message, 1);
    }

    private void assertExitCode(@Nullable String message, int exitCode) {
      if (exitCode == getExitCode()) {
        return;
      }

      String failureMessage = String.format(
          "Expected exit code %d but was %d.", exitCode, getExitCode());
      if (message != null) {
        failureMessage = message + " " + failureMessage;
      }

      System.err.println("=== " + failureMessage + " ===");
      System.err.println("=== STDERR ===");
      System.err.println(getStderr());
      System.err.println("=== STDOUT ===");
      System.err.println(getStdout());
      fail(failureMessage);
    }

    public void assertSpecialExitCode(String message, int exitCode) {
      assertExitCode(message, exitCode);
    }
  }

  /**
   * For every file in the template directory whose name ends in {@code .expected}, checks that an
   * equivalent file has been written in the same place under the destination directory.
   */
  public void verify() throws IOException {
    SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(EXPECTED_SUFFIX)) {
          // Get File for the file that should be written, but without the ".expected" suffix.
          Path generatedFileWithSuffix = destPath.resolve(templatePath.relativize(file));
          File directory = generatedFileWithSuffix.getParent().toFile();
          File observedFile = new File(directory, Files.getNameWithoutExtension(fileName));

          if (!observedFile.isFile()) {
            fail("Expected file " + observedFile + " could not be found.");
          }
          String expectedFileContent = Files.toString(file.toFile(), Charsets.UTF_8);
          String observedFileContent = Files.toString(observedFile, Charsets.UTF_8);
          observedFileContent = observedFileContent.replace("\r\n", "\n");
          String cleanPathToObservedFile = MoreStrings.withoutSuffix(
              templatePath.relativize(file).toString(), EXPECTED_SUFFIX);
          assertEquals(
              String.format(
                  "In %s, expected content of %s to match that of %s.",
                  cleanPathToObservedFile,
                  expectedFileContent,
                  observedFileContent),
              expectedFileContent,
              observedFileContent);
        }
        return FileVisitResult.CONTINUE;
      }
    };
    java.nio.file.Files.walkFileTree(templatePath, copyDirVisitor);
  }

  /**
   * NGContext test double.
   */
  private class TestContext extends NGContext {

    Properties properties;

    public TestContext(ImmutableMap<String, String> environment) {
      in = new ByteArrayInputStream(new byte[0]);
      setExitStream(new CapturingPrintStream());
      properties = new Properties();
      for (String key : environment.keySet()) {
        properties.setProperty(key, environment.get(key));
      }
    }

    @Override
    public void addClientListener(NGClientListener listener) {
    }

    @Override
    public Properties getEnv() {
      return properties;
    }
  }
}
