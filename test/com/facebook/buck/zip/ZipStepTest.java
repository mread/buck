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

package com.facebook.buck.zip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class ZipStepTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void shouldCreateANewZipFileFromScratch() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    Files.touch(new File(toZip, "file2.txt"));
    Files.touch(new File(toZip, "file3.txt"));

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.<String>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    step.execute(TestExecutionContext.newInstance());

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt", "file2.txt", "file3.txt"), zip.getFileNames());
    }
  }

  @Test
  public void willOnlyIncludeEntriesInThePathsArgumentIfAnyAreSet() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    Files.touch(new File(toZip, "file2.txt"));
    Files.touch(new File(toZip, "file3.txt"));

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.of("file2.txt"),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    step.execute(TestExecutionContext.newInstance());

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file2.txt"), zip.getFileNames());
    }
  }

  @Test
  public void willRecurseIntoSubdirectories() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    assertTrue(new File(toZip, "child").mkdir());
    Files.touch(new File(toZip, "child/file2.txt"));

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.<String>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    step.execute(TestExecutionContext.newInstance());

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt", "child/file2.txt"), zip.getFileNames());
    }
  }

  @Test
  public void mustIncludeTheContentsOfFilesThatAreSymlinked() throws IOException {
    // Symlinks on Windows are _hard_. Let's go shopping.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");
    File target = new File(parent, "target");
    Files.write("example content", target, UTF_8);

    File toZip = tmp.newFolder("zipdir");
    Path path = toZip.toPath().resolve("file.txt");
    java.nio.file.Files.createSymbolicLink(path, target.toPath());

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.<String>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    step.execute(TestExecutionContext.newInstance());

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file.txt"), zip.getFileNames());
      byte[] contents = zip.readFully("file.txt");

      assertArrayEquals("example content".getBytes(), contents);
    }
  }

  @Test
  public void overwritingAnExistingZipFileIsAnError() throws IOException {
    File parent = tmp.newFolder();
    File out = new File(parent, "output.zip");

    try (Zip zip = new Zip(out, true)) {
      zip.add("file1.txt", "");
    }

    File toZip = tmp.newFolder();

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.<String>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    int result = step.execute(TestExecutionContext.newInstance());

    assertEquals(1, result);
  }

  @Test
  public void shouldBeAbleToJunkPaths() throws IOException {
    File parent = tmp.newFolder();
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder();
    assertTrue(new File(toZip, "child").mkdir());
    Files.touch(new File(toZip, "child/file1.txt"));

    ZipStep step = new ZipStep(out.getAbsolutePath(),
        ImmutableSet.<String>of(),
        true,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        toZip);
    step.execute(TestExecutionContext.newInstance());

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt"), zip.getFileNames());
    }
  }
}
