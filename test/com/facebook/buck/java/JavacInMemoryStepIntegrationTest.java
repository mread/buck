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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JavacInMemoryStepIntegrationTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private Path pathToSrcsList;

  @Before
  public void setUp() {
    pathToSrcsList = Paths.get(tmp.getRoot().getPath(), "srcs_list");
  }

  @Test
  public void testGetDescription() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    String pathToOutputDir = new File(tmp.getRoot(), "out").getAbsolutePath();
    String pathToAbiFile = new File(tmp.getRoot(), "abi").getAbsolutePath();
    assertEquals(
        String.format("javac -target 6 -source 6 -g " +
            "-processorpath %s " +
            "-processor %s " +
            "-A%s=%s " +
            "-d %s " +
            "-classpath '' " +
            "@" + pathToSrcsList.toString(),
            AbiWritingAnnotationProcessingDataDecorator.ABI_PROCESSOR_CLASSPATH,
            AbiWriterProtocol.ABI_ANNOTATION_PROCESSOR_CLASS_NAME,
            AbiWriterProtocol.PARAM_ABI_OUTPUT_FILE,
            pathToAbiFile,
            pathToOutputDir),
        javac.getDescription(executionContext));
  }

  @Test
  public void testGetShortName() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ false);
    assertEquals("javac", javac.getShortName());
  }

  @Test
  public void testGetAbiKeyOnSuccessfulCompile() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    int exitCode = javac.execute(executionContext);
    assertEquals("javac should exit with code 0.", exitCode, 0);
    assertEquals(new Sha1HashCode("65386ff045e932d8ba6444043132c140f76a4613"), javac.getAbiKey());
  }

  @Test
  public void testGetAbiKeyOnFailedCompile() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ true);
    ExecutionContext executionContext = createExecutionContext();
    int exitCode = javac.execute(executionContext);
    assertEquals("javac should exit with code 1 due to sytnax error.", exitCode, 1);
    assertEquals("ABI key will not be available when compilation fails.", null, javac.getAbiKey());
  }

  @Test
  public void testGetAbiKeyThrowsIfNotBuilt() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ false);
    try {
      javac.getAbiKey();
      fail("Should have thrown IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals("Must execute step before requesting AbiKey.", e.getMessage());
    }
  }

  @Test
  public void testClassesFile() throws IOException {
    JavacInMemoryStep javac = createJavac(/* withSyntaxError */ false);
    ExecutionContext executionContext = createExecutionContext();
    int exitCode = javac.execute(executionContext);
    assertEquals("javac should exit with code 0.", exitCode, 0);

    File srcsListFile = pathToSrcsList.toFile();
    assertTrue(srcsListFile.exists());
    assertTrue(srcsListFile.isFile());
    assertEquals("Example.java", Files.toString(srcsListFile, Charsets.UTF_8).trim());
  }

  private JavacInMemoryStep createJavac(boolean withSyntaxError) throws IOException {
    File exampleJava = tmp.newFile("Example.java");
    Files.write(Joiner.on('\n').join(
            "package com.example;",
            "",
            "public class Example {" +
            (withSyntaxError ? "" : "}")
        ),
        exampleJava,
        Charsets.UTF_8);

    Path pathToOutputDirectory = Paths.get("out");
    tmp.newFolder(pathToOutputDirectory.toString());
    Path pathToOutputAbiFile = Paths.get("abi");
    return new JavacInMemoryStep(
        pathToOutputDirectory,
        /* javaSourceFilePaths */ ImmutableSet.of(Paths.get("Example.java")),
        /* transitive classpathEntries */ ImmutableSet.<String>of(),
        /* declated classpathEntries */ ImmutableSet.<String>of(),
        JavacOptions.builder().build(),
        Optional.of(pathToOutputAbiFile),
        Optional.<String>absent(),
        BuildDependencies.FIRST_ORDER_ONLY,
        Optional.<JavacInMemoryStep.SuggestBuildRules>absent(),
        Optional.of(pathToSrcsList));
  }

  private ExecutionContext createExecutionContext() {
    return TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp.getRoot()))
        .build();
  }
}
