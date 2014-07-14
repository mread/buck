/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

public class FakeProjectFilesystemTest {
  @Test
  public void testFilesystemReturnsAddedContents() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("Some content", Paths.get("A.txt"));

    Optional<String> contents;
    contents = filesystem.readFileIfItExists(Paths.get("A.txt"));
    assertTrue("Fake file system must return added file contents.", contents.isPresent());
    assertEquals("Some content", contents.get());

    contents = filesystem.readFileIfItExists(Paths.get("B.txt"));
    assertFalse(
        "Fake file system must not return non-existing file contents",
        contents.isPresent());
  }

  @Test
  public void testReadLines() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.writeContentsToPath("line one.\nline two.\n", Paths.get("A.txt"));
    filesystem.writeLinesToPath(ImmutableList.<String>of(), Paths.get("B.txt"));
    filesystem.writeContentsToPath("\n", Paths.get("C.txt"));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of("line one.", "line two."),
        filesystem.readLines(Paths.get("A.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(),
        filesystem.readLines(Paths.get("B.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(""),
        filesystem.readLines(Paths.get("C.txt")));

    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(),
        filesystem.readLines(Paths.get("D.txt")));
  }

  @Test
  public void testTouch() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("A.txt"));
    filesystem.touch(Paths.get("A/B.txt"));

    assertTrue(filesystem.exists(Paths.get("A.txt")));
    assertTrue(filesystem.exists(Paths.get("A/B.txt")));
  }

  @Test
  public void testWalkRelativeFileTree() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    filesystem.touch(Paths.get("root/A.txt"));
    filesystem.touch(Paths.get("root/A/B/C.txt"));
    filesystem.touch(Paths.get("root/A/B.txt"));

    final List<Path> filesVisited = Lists.newArrayList();

    FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
        filesVisited.add(path);
        return FileVisitResult.CONTINUE;
      }
    };

    filesystem.walkRelativeFileTree(Paths.get("root"), fileVisitor);
    assertThat(filesVisited, containsInAnyOrder(
            Paths.get("root/A.txt"),
            Paths.get("root/A/B/C.txt"),
            Paths.get("root/A/B.txt")));

    filesVisited.clear();
    filesystem.walkRelativeFileTree(Paths.get("root/A"), fileVisitor);
    assertThat(
        filesVisited, containsInAnyOrder(
            Paths.get("root/A/B/C.txt"),
            Paths.get("root/A/B.txt")));
  }

  @Test
  public void testNewFileInputStream() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath("hello world", path);
    InputStreamReader reader = new InputStreamReader(
        filesystem.newFileInputStream(path), Charsets.UTF_8);
    String contents = CharStreams.toString(reader);
    assertEquals("hello world", contents);
  }

  @Test
  public void testAllExistingFileSystem() throws IOException {
    AllExistingProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    assertTrue(filesystem.exists(Paths.get("somepath.txt")));
  }

  @Test
  public void testWriteContentsWithDefaultFileAttributes() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath("hello world", path);
    assertEquals(ImmutableSet.<FileAttribute<?>>of(), filesystem.getFileAttributesAtPath(path));
  }

  @Test
  public void testWriteContentsWithSpecifiedFileAttributes() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableSet<PosixFilePermission> permissions =
      ImmutableSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);
    FileAttribute<?> attribute = PosixFilePermissions.asFileAttribute(permissions);

    Path path = Paths.get("hello.txt");
    filesystem.writeContentsToPath(
        "hello world",
        Paths.get("hello.txt"),
        attribute);
    assertEquals(ImmutableSet.of(attribute), filesystem.getFileAttributesAtPath(path));
  }
}
