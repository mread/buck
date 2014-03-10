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

package com.facebook.buck.util;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.WatchEvents;
import com.facebook.buck.util.ProjectFilesystem.CopySourceMode;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;

/** Unit test for {@link ProjectFilesystem}. */
public class ProjectFilesystemTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testIsFile() throws IOException {
    tmp.newFolder("foo");
    tmp.newFile("foo/bar.txt");

    assertTrue(filesystem.isFile(Paths.get("foo/bar.txt")));
    assertFalse(filesystem.isFile(Paths.get("i_do_not_exist")));
    assertFalse(
        "foo/ is a directory, but not an ordinary file",
        filesystem.isFile(Paths.get("foo")));
  }

  @Test
  public void testIsDirectory() throws IOException {
    File dir = tmp.newFolder("src");
    File file = tmp.newFile("BUCK");
    assertTrue(filesystem.isDirectory(Paths.get(dir.getName())));
    assertFalse(filesystem.isDirectory(Paths.get(file.getName())));
  }

  @Test
  public void testMkdirsCanCreateNestedFolders() throws IOException {
    filesystem.mkdirs(new File("foo/bar/baz").toPath());
    assertTrue(new File(tmp.getRoot(), "foo/bar/baz").isDirectory());
  }

  @Test
  public void testCreateParentDirs() throws IOException {
    Path pathRelativeToProjectRoot = Paths.get("foo/bar/baz.txt");
    filesystem.createParentDirs(pathRelativeToProjectRoot);
    assertTrue(new File(tmp.getRoot(), "foo").isDirectory());
    assertTrue(new File(tmp.getRoot(), "foo/bar").isDirectory());
    assertFalse(
        "createParentDirs() should create directories, but not the leaf/file part of the path.",
        new File(tmp.getRoot(), "foo/bar/baz.txt").exists());
  }

  @Test(expected = NullPointerException.class)
  public void testReadFirstLineRejectsNullString() {
    filesystem.readFirstLine(/* pathRelativeToProjectRoot */ (String) null);
  }

  @Test(expected = NullPointerException.class)
  public void testReadFirstLineRejectsNullPath() {
    filesystem.readFirstLine(/* pathRelativeToProjectRoot */ (Path) null);
  }

  @Test
  public void testReadFirstLineToleratesNonExistentFile() {
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineWithEmptyFile() throws IOException {
    File emptyFile = tmp.newFile("foo.txt");
    Files.write(new byte[0], emptyFile);
    assertTrue(emptyFile.isFile());
    assertEquals(Optional.absent(), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void testReadFirstLineFromMultiLineFile() throws IOException {
    File multiLineFile = tmp.newFile("foo.txt");
    Files.write("foo\nbar\nbaz\n", multiLineFile, Charsets.UTF_8);
    assertEquals(Optional.of("foo"), filesystem.readFirstLine("foo.txt"));
  }

  @Test
  public void getReaderIfFileExists() throws IOException {
    File file = tmp.newFile("foo.txt");
    Files.write("fooooo\nbar\nbaz\n", file, Charsets.UTF_8);
    assertEquals(
        "fooooo\nbar\nbaz\n",
        CharStreams.toString(filesystem.getReaderIfFileExists(Paths.get("foo.txt")).get())
    );
  }

  @Test
  public void getReaderIfFileExistsNoFile() throws IOException {
    assertEquals(Optional.absent(), filesystem.getReaderIfFileExists(Paths.get("foo.txt")));
  }

  @Test
  public void testGetFileSize() throws IOException {
    File wordsFile = tmp.newFile("words.txt");
    String content = "Here\nare\nsome\nwords.\n";
    Files.write(content, wordsFile, Charsets.UTF_8);

    assertEquals(content.length(), filesystem.getFileSize(Paths.get("words.txt")));
  }

  @Test(expected = IOException.class)
  public void testGetFileSizeThrowsForNonExistentFile() throws IOException {
    filesystem.getFileSize(Paths.get("words.txt"));
  }

  @Test
  public void testWriteLinesToPath() throws IOException {
    Iterable<String> lines = ImmutableList.of("foo", "bar", "baz");
    filesystem.writeLinesToPath(lines, Paths.get("lines.txt"));

    String contents = Files.toString(new File(tmp.getRoot(), "lines.txt"), Charsets.UTF_8);
    assertEquals("foo\nbar\nbaz\n", contents);
  }

  @Test
  public void testWriteBytesToPath() throws IOException {
    String content = "Hello, World!";
    byte[] bytes = content.getBytes();
    filesystem.writeBytesToPath(bytes, Paths.get("hello.txt"));
    assertEquals(content, Files.toString(new File(tmp.getRoot(), "hello.txt"), Charsets.UTF_8));
  }

  @Test
  public void testCopyToPath() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("Hello, world!".getBytes());
    filesystem.copyToPath(inputStream, Paths.get("bytes.txt"));

    assertEquals(
        "The bytes on disk should match those from the InputStream.",
        "Hello, world!",
        Files.toString(new File(tmp.getRoot(), "bytes.txt"), Charsets.UTF_8));
  }

  @Test
  public void testCopyFolder() throws IOException {
    // Build up a directory of dummy files.
    tmp.newFolder("src");
    tmp.newFolder("src/com");
    tmp.newFolder("src/com/example");
    tmp.newFolder("src/com/example/foo");
    tmp.newFile("src/com/example/foo/Foo.java");
    tmp.newFile("src/com/example/foo/package.html");
    tmp.newFolder("src/com/example/bar");
    tmp.newFile("src/com/example/bar/Bar.java");
    tmp.newFile("src/com/example/bar/package.html");

    // Copy the contents of src/ to dest/.
    tmp.newFolder("dest");
    filesystem.copyFolder(Paths.get("src"), Paths.get("dest"));

    assertTrue(new File(tmp.getRoot(), "dest/com/example/foo/Foo.java").exists());
    assertTrue(new File(tmp.getRoot(), "dest/com/example/foo/package.html").exists());
    assertTrue(new File(tmp.getRoot(), "dest/com/example/bar/Bar.java").exists());
    assertTrue(new File(tmp.getRoot(), "dest/com/example/bar/package.html").exists());
  }

  @Test
  public void testCopyFolderAndContents() throws IOException {
    // Build up a directory of dummy files.
    tmp.newFolder("src");
    tmp.newFolder("src/com");
    tmp.newFolder("src/com/example");
    tmp.newFolder("src/com/example/foo");
    tmp.newFile("src/com/example/foo/Foo.java");
    tmp.newFile("src/com/example/foo/package.html");
    tmp.newFolder("src/com/example/bar");
    tmp.newFile("src/com/example/bar/Bar.java");
    tmp.newFile("src/com/example/bar/package.html");

    // Copy the contents of src/ to dest/ (including src itself).
    tmp.newFolder("dest");
    filesystem.copy(Paths.get("src"), Paths.get("dest"), CopySourceMode.DIRECTORY_AND_CONTENTS);

    assertTrue(new File(tmp.getRoot(), "dest/src/com/example/foo/Foo.java").exists());
    assertTrue(new File(tmp.getRoot(), "dest/src/com/example/foo/package.html").exists());
    assertTrue(new File(tmp.getRoot(), "dest/src/com/example/bar/Bar.java").exists());
    assertTrue(new File(tmp.getRoot(), "dest/src/com/example/bar/package.html").exists());
  }

  @Test
  public void testCopyFile() throws IOException {
    tmp.newFolder("foo");
    File file = tmp.newFile("foo/bar.txt");
    String content = "Hello, World!";
    Files.write(content, file, Charsets.UTF_8);

    filesystem.copyFile(Paths.get("foo/bar.txt"), Paths.get("foo/baz.txt"));
    assertEquals(content, Files.toString(new File(tmp.getRoot(), "foo/baz.txt"), Charsets.UTF_8));
  }

  @Test
  public void testDeleteFileAtPath() throws IOException {
    Path path = Paths.get("foo.txt");
    File file = tmp.newFile(path.toString());
    assertTrue(file.exists());
    filesystem.deleteFileAtPath(path);
    assertFalse(file.exists());
  }

  @Test
  public void testCreateContextStringForModifyEvent() throws IOException {
    File file = tmp.newFile("foo.txt");
    WatchEvent<Path> modifyEvent = WatchEvents.createPathEvent(
        file,
        StandardWatchEventKinds.ENTRY_MODIFY);
    assertEquals(file.getAbsolutePath(), filesystem.createContextString(modifyEvent));
  }

  @Test
  public void testCreateContextStringForOverflowEvent() {
    WatchEvent<Object> overflowEvent = new WatchEvent<Object>() {
      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 0;
      }

      @Override
      public Object context() {
        return new Object() {
          @Override
          public String toString() {
            return "I am the context string.";
          }
        };
      }
    };
    assertEquals("I am the context string.", filesystem.createContextString(overflowEvent));
  }

  @Test
  public void testWalkFileTreeWhenProjectRootIsNotWorkingDir() throws IOException {
    tmp.newFolder("dir");
    tmp.newFile("dir/file.txt");
    tmp.newFolder("dir/dir2");
    tmp.newFile("dir/dir2/file2.txt");

    final ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    filesystem.walkRelativeFileTree(
        Paths.get("dir"), new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(fileNames.build(), containsInAnyOrder("file.txt", "file2.txt"));
  }

  @Test
  public void testWalkFileTreeWhenProjectRootIsWorkingDir() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get("."));
    final ImmutableList.Builder<String> fileNames = ImmutableList.builder();

    Path pathRelativeToProjectRoot = Paths.get("test/com/facebook/buck/util/testdata");
    projectFilesystem.walkRelativeFileTree(
        pathRelativeToProjectRoot,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileNames.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        }
    );

    assertThat(fileNames.build(), containsInAnyOrder(
            "file",
            "a_file",
            "b_file",
            "b_c_file",
            "b_d_file"));
  }
}

