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

package com.facebook.buck.cli;

import static com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DaemonIntegrationTest {

  private static final int SUCCESS_EXIT_CODE = 0;
  private ScheduledExecutorService executorService;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void setUp() {
    executorService = Executors.newScheduledThreadPool(2);
  }

  @After
  public void tearDown() {
    executorService.shutdown();
    Main.resetDaemon();
  }

  /**
   * This verifies that when the user tries to run the Buck Main method, while it is already
   * running, the second call will fail. Serializing command execution in this way avoids
   * multiple threads accessing and corrupting the static state used by the Buck daemon.
   */
  @Test
  public void testExclusiveExecution()
      throws IOException, InterruptedException, ExecutionException {
    final CapturingPrintStream stdOut = new CapturingPrintStream();
    final CapturingPrintStream firstThreadStdErr = new CapturingPrintStream();
    final CapturingPrintStream secondThreadStdErr = new CapturingPrintStream();

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    Future<?> firstThread = executorService.schedule(new Runnable() {
      @Override
      public void run() {
        try {
          Main main = new Main(stdOut, firstThreadStdErr);
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(),
              Optional.<NGContext>absent(),
              "build",
              "//:sleep");
          assertEquals("Should return 0 when no command running.", SUCCESS_EXIT_CODE, exitCode);
        } catch (IOException e) {
          fail("Should not throw exception.");
          throw Throwables.propagate(e);
        } catch (InterruptedException e) {
          fail("Should not throw exception.");
          Thread.currentThread().interrupt();
        }
      }
    }, 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread = executorService.schedule(new Runnable() {
      @Override
      public void run() {
        try {
          Main main = new Main(stdOut, secondThreadStdErr);
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(),
              Optional.<NGContext>absent(),
              "targets");
          assertEquals("Should return 2 when command running.", Main.BUSY_EXIT_CODE, exitCode);
        } catch (IOException e) {
          fail("Should not throw exception.");
          throw Throwables.propagate(e);
        } catch (InterruptedException e) {
          fail("Should not throw exception.");
          Thread.currentThread().interrupt();
        }
      }
    }, 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /**
   * Verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from a blocking heartbeat stream.
   */
  @Test(expected = InterruptedException.class, timeout = 500) // Test should be interrupted.
  public void whenClientTimeoutDetectedThenMainThreadIsInterrupted()
      throws InterruptedException, IOException {
    final long timeoutMillis = 100;
    final long intervalMillis = timeoutMillis * 2; // Interval > timeout to trigger disconnection.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from a stream that will timeout.
    Thread.currentThread().setName("Test");
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createHeartBeatStream(intervalMillis),
        timeoutMillis)) {
      final Thread commandThread = Thread.currentThread();
      context.addClientListener(
          new NGClientListener() {
            @Override
            public void clientDisconnected() throws InterruptedException {
              commandThread.interrupt();
            }
          });
      Thread.sleep(1000);
      fail("Should have been interrupted.");
    }
  }

  /**
   * This verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will cause command execution to fail after timeout.
   */
  @Test(timeout = 500) // Test should be interrupted.
  public void whenClientTimeoutDetectedThenBuildIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 100;
    final long intervalMillis = timeoutMillis * 2; // Interval > timeout to trigger disconnection.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createHeartBeatStream(intervalMillis),
        timeoutMillis)) {
      workspace.runBuckdCommand(context, "build", "//:sleep").assertFailure();
    }
  }

  /**
   * This verifies that a client disconnection will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will interrupt command execution causing it to fail.
   */
  @Test(timeout = 500)
  public void whenClientTimeoutDetectedThenTestIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 100;
    final long intervalMillis = timeoutMillis * 2; // Interval > timeout to trigger disconnection.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createHeartBeatStream(intervalMillis),
        timeoutMillis)) {
      workspace.runBuckdCommand(context, "test", "//:test").assertFailure();
    }
  }

  /**
   * @param disconnectMillis duration to wait before generating IOException.
   * @return an InputStream which will wait and then simulate a client disconnection.
   */
  private static InputStream createDisconnectionStream(final long disconnectMillis) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        try {
          Thread.sleep(disconnectMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        throw new IOException("Fake client disconnection.");
      }
    };
  }

  /**
   * This verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will cause command execution to fail after timeout.
   */
  @Test(timeout = 500) // Test should be interrupted.
  public void whenClientDisconnectionDetectedThenBuildIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 2000; // Stream timeout > test timeout.
    final long disconnectMillis = 100; // Disconnect before test timeout.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        createDisconnectionStream(disconnectMillis),
        timeoutMillis)) {
      workspace.runBuckdCommand(context, "build", "//:sleep").assertFailure();
    }
  }

  /**
   * This verifies that a client disconnection will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will interrupt command execution causing it to fail.
   */
  @Test(timeout = 500)
  public void whenClientDisconnectionDetectedThenTestIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 2000; // Stream timeout > test timeout.
    final long disconnectMillis = 100; // Disconnect before test timeout.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        createDisconnectionStream(disconnectMillis),
        timeoutMillis)) {
      workspace.runBuckdCommand(context, "test", "//:test").assertFailure();
    }
  }

  @Test
  public void whenAppBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    result.assertSuccess();

    String fileName = "apps/myapp/BUCK";
    assertTrue("Should delete BUCK file successfully", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    try {
      workspace.runBuckdCommand("build", "app");
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertThat("Failure should have been due to BUCK file removal.", e.getMessage(),
          containsString(fileName));
    }
  }

  @Test
  public void whenActivityBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/BUCK";
    assertTrue("Should delete BUCK file successfully.", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertFailure();
  }

  @Test
  public void whenSourceInputRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    assertTrue("Should delete BUCK file successfully.", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    try {
      workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
      fail("Should have thrown HumanReadableException.");
    } catch (java.lang.RuntimeException e) {
      assertThat("Failure should have been due to file removal.", e.getMessage(),
          containsString("MyFirstActivity.java"));
    }
  }

  @Test
  public void whenSourceInputInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.write("Some Illegal Java".getBytes(Charsets.US_ASCII), workspace.getFile(fileName));
    waitForChange(Paths.get(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertFailure();
  }

  @Test
  public void whenAppBuckFileInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "app").assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.write("Some Illegal Python".getBytes(Charsets.US_ASCII), workspace.getFile(fileName));
    waitForChange(Paths.get(fileName));

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    assertThat(
        "Failure should be due to syntax error.",
        result.getStderr(),
        containsString("SyntaxError: invalid syntax"));
    result.assertFailure();
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    ProcessResult rebuild =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity", "-v", "10");
    rebuild.assertSuccess();

    assertThat("Noop build should not have reparsed.",
        rebuild.getStderr(),
        not(containsString("Parsing")));

    String buckConfigFilename = ".buckconfig";
    String extraConfigOptions = Joiner.on("\n").join(
        "[ndk]",
        "    ndk_version = r9b",
        "");

    Files.append(extraConfigOptions, workspace.getFile(buckConfigFilename), Charsets.UTF_8);

    rebuild =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity", "-v", "10");
    rebuild.assertSuccess();

    assertThat("Changing .buckconfing should have forced a reparse.",
        rebuild.getStderr(),
        containsString("Parsing"));
  }

  @Test
  public void whenBuckBuiltTwiceLogIsPresent()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    File buildLogFile = workspace.getFile("buck-out/bin/build.log");

    assertTrue(buildLogFile.isFile());
    assertTrue(buildLogFile.delete());

    ProcessResult rebuild =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
    rebuild.assertSuccess();

    buildLogFile = workspace.getFile("buck-out/bin/build.log");
    assertTrue(buildLogFile.isFile());
  }

  private void waitForChange(final Path path) throws IOException, InterruptedException {

    class Watcher {
      private Path path;
      private boolean watchedChange = false;

      public Watcher(Path path) {
        this.path = path;
        watchedChange = false;
      }

      public boolean watchedChange() {
        return watchedChange;
      }

      @Subscribe
      public synchronized void onEvent(WatchEvent<?> event) throws IOException {
        if (path.equals(event.context())) {
          watchedChange = true;
        }
      }
    }

    Watcher watcher = new Watcher(path);
    Main.registerFileWatcher(watcher);
    while (!watcher.watchedChange()) {
      Main.watchFilesystem();
    }
  }
}
