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

package com.facebook.buck.java;

import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class JarDirectoryStepTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void shouldNotThrowAnExceptionWhenAddingDuplicateEntries() throws IOException {
    File zipup = folder.newFolder("zipup");

    File first = createZip(new File(zipup, "a.zip"), "example.txt");
    File second = createZip(new File(zipup, "b.zip"), "example.txt");

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(Paths.get(first.getName()), Paths.get(second.getName())),
        "com.example.Main",
        /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup))
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");
    assertTrue(zip.exists());

    // "example.txt" and the MANIFEST.MF.
    assertZipFileCountIs(2, zip);
    assertZipContains(zip, "example.txt");
  }

  @Test
  public void shouldNotComplainWhenDuplicateDirectoryNamesAreAdded() throws IOException {
    File zipup = folder.newFolder();

    File first = createZip(new File(zipup, "first.zip"), "dir/example.txt", "dir/root1file.txt");
    File second = createZip(new File(zipup, "second.zip"), "dir/example.txt", "dir/root2file.txt");

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(Paths.get(first.getName()), Paths.get(second.getName())),
        "com.example.Main",
        /* manifest file */ null);

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup))
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");

    // The three below plus the manifest.
    assertZipFileCountIs(4, zip);
    assertZipContains(zip, "dir/example.txt", "dir/root1file.txt", "dir/root2file.txt");
  }

  @Test
  public void entriesFromTheGivenManifestShouldOverrideThoseInTheJars() throws IOException {
    String expected = "1.4";
    // Write the manifest, setting the implementation version
    File tmp = folder.newFolder();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), expected);
    File manifestFile = new File(tmp, "manifest");
    try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
      manifest.write(fos);
    }

    // Write another manifest, setting the implementation version to something else
    manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), "1.0");

    File input = new File(tmp, "input.jar");
    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(input)) {
      ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
      out.putNextEntry(entry);
      manifest.write(out);
    }

    File output = new File(tmp, "output.jar");
    JarDirectoryStep step = new JarDirectoryStep(
        Paths.get("output.jar"),
        ImmutableSet.of(Paths.get("input.jar")),
        /* main class */ null,
        Paths.get("manifest"));
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp))
        .build();
    assertEquals(0, step.execute(context));

    try (Zip zip = new Zip(output, false)) {
      byte[] rawManifest = zip.readFully("META-INF/MANIFEST.MF");
      manifest = new Manifest(new ByteArrayInputStream(rawManifest));
      String version = manifest.getMainAttributes().getValue(IMPLEMENTATION_VERSION);

      assertEquals(expected, version);
    }
  }

  private File createZip(File zipFile, String... fileNames) throws IOException {
    try (Zip zip = new Zip(zipFile, true)) {
      for (String fileName : fileNames) {
        zip.add(fileName, "");
      }
    }
    return zipFile;
  }

  private void assertZipFileCountIs(int expected, File zip) throws IOException {
    Set<String> fileNames = getFileNames(zip);

    assertEquals(fileNames.toString(), expected, fileNames.size());
  }

  private void assertZipContains(File zip, String... files) throws IOException {
    final Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertTrue(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private Set<String> getFileNames(File zipFile) throws IOException {
    try (Zip zip = new Zip(zipFile, false)) {
      return zip.getFileNames();
    }
  }

}
