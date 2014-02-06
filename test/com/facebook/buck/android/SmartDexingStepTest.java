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

package com.facebook.buck.android;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.SmartDexingStep.DxPseudoRule;
import com.facebook.buck.android.SmartDexingStep.InputResolver;
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SmartDexingStepTest extends EasyMockSupport {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  /**
   * This test makes sure the input processing builds the correct pair of output filenames and
   * input arguments that are ultimately passed to dx.
   */
  @Test
  public void testInputResolverWithMultipleOutputs() throws IOException {
    File primaryOutDir = tmpDir.newFolder("primary-out");
      File primaryOut = new File(primaryOutDir, "primary.jar");
    Set<Path> primaryIn = ImmutableSet.of(
        Paths.get("input/a.jar"), Paths.get("input/b.jar"), Paths.get("input/c.jar"));
    File secondaryOutDir = tmpDir.newFolder("secondary-out");
    File secondaryInDir = tmpDir.newFolder("secondary-in");
    File secondaryInFile = new File(secondaryInDir, "2.jar");
    Files.write(new byte[]{0}, secondaryInFile);

    Multimap<Path, Path> secondaryOutputToInputFiles =
        ImmutableListMultimap.of(
            Paths.get(new File(secondaryOutDir, "2.dex.jar").getPath()),
            Paths.get(secondaryInFile.getPath()));
    InputResolver resolver = new InputResolver(
        Paths.get("primary-out/primary.jar"),
        Suppliers.ofInstance(primaryIn),
        Optional.of(Paths.get("secondary-out")),
        Optional.of(Suppliers.ofInstance(secondaryOutputToInputFiles)));
    assertTrue("Expected secondary output", resolver.hasSecondaryOutput());
    final ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    Multimap<File, File> outputToInputs = resolver.createOutputToInputs(projectFilesystem);
    assertEquals("Expected 2 output artifacts", 2, outputToInputs.keySet().size());

    MoreAsserts.assertIterablesEquals(
        "Detected inconsistency with primary input arguments",
        Iterables.transform(primaryIn, new Function<Path, File>() {
          @Override
          public File apply(Path input) {
            return projectFilesystem.getFileForRelativePath(input);
          }
        }),
        outputToInputs.get(primaryOut));

    // Make sure that secondary-out/2.dex.jar came from secondary-in/2.jar.
    File secondaryOutFile = new File(secondaryOutDir, "2.dex.jar");
    MoreAsserts.assertIterablesEquals(
        "Detected inconsistency with secondary output arguments",
        ImmutableSet.of(secondaryInFile),
        outputToInputs.get(secondaryOutFile));
  }

  /**
   * Tests whether pseudo rule cache detection is working properly.
   */
  @Test
  public void testDxPseudoRuleCaching() throws IOException {
    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    File testIn = new File(tmpDir.getRoot(), "testIn");
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(testIn)));
    try {
      zipOut.putNextEntry(new ZipEntry("foobar"));
      zipOut.write(new byte[] { 0 });
    } finally {
      zipOut.close();
    }

    File outputFile = tmpDir.newFile("out.dex");
    Path outputHashFile = new File(tmpDir.getRoot(), "out.dex.hash").toPath();
    Files.write("dummy", outputHashFile.toFile(), Charsets.UTF_8);

    DxPseudoRule rule = new DxPseudoRule(context,
        ImmutableSet.of(testIn.toPath()),
        outputFile.toPath(),
        outputHashFile,
        EnumSet.of(DxStep.Option.NO_OPTIMIZE));
    assertFalse("'dummy' is not a matching input hash", rule.checkIsCached());

    // Write the real hash into the output hash file and ensure that checkIsCached now
    // yields true.
    String actualHash = rule.hashInputs();
    assertFalse(actualHash.isEmpty());
    Files.write(actualHash, outputHashFile.toFile(), Charsets.UTF_8);

    assertTrue("Matching input hash should be considered cached", rule.checkIsCached());
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithXzOutput() {
    ImmutableList<Path> filesToDex = ImmutableList.of(
        Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex.jar.xz");
    EnumSet<DxStep.Option> dxOptions = EnumSet.noneOf(DxStep.Option.class);
    Step dxStep = SmartDexingStep.createDxStepForDxPseudoRule(filesToDex, outputPath, dxOptions);

    assertTrue("Result should be a CompositeStep.", dxStep instanceof CompositeStep);
    List<Step> steps = ImmutableList.copyOf((CompositeStep) dxStep);
    String xmx = DxStep.XMX_OVERRIDE.isEmpty() ? "" : DxStep.XMX_OVERRIDE + " ";
    MoreAsserts.assertSteps(
        "Steps should repack zip entries and then compress using xz.",
        ImmutableList.of(
            "/usr/bin/dx " + xmx + "--dex --output classes.dex.tmp.jar foo.dex.jar bar.dex.jar",
            "repack classes.dex.tmp.jar in classes.dex.jar",
            "rm -f classes.dex.tmp.jar",
            "xz -z -4 --check=crc32 classes.dex.jar"),
        steps,
        createMockedExecutionContext());

    verifyAll();
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithDexOutput() {
    ImmutableList<Path> filesToDex = ImmutableList.of(
        Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex");
    EnumSet<DxStep.Option> dxOptions = EnumSet.noneOf(DxStep.Option.class);
    Step dxStep = SmartDexingStep.createDxStepForDxPseudoRule(filesToDex, outputPath, dxOptions);

    String xmx = DxStep.XMX_OVERRIDE.isEmpty() ? "" : DxStep.XMX_OVERRIDE + " ";
    assertEquals(
        "/usr/bin/dx " + xmx + "--dex --output classes.dex foo.dex.jar bar.dex.jar",
        dxStep.getDescription(createMockedExecutionContext()));
    verifyAll();
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithDexJarOutput() {
    ImmutableList<Path> filesToDex = ImmutableList.of(
        Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex.jar");
    EnumSet<DxStep.Option> dxOptions = EnumSet.noneOf(DxStep.Option.class);
    Step dxStep = SmartDexingStep.createDxStepForDxPseudoRule(filesToDex, outputPath, dxOptions);

    String xmx = DxStep.XMX_OVERRIDE.isEmpty() ? "" : DxStep.XMX_OVERRIDE + " ";
    assertEquals(
        "/usr/bin/dx " + xmx + "--dex --output classes.dex.jar foo.dex.jar bar.dex.jar",
        dxStep.getDescription(createMockedExecutionContext()));
    verifyAll();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreateDxStepForDxPseudoRuleWithUnrecognizedOutput() {
    ImmutableList<Path> filesToDex = ImmutableList.of(
        Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.flex");
    EnumSet<DxStep.Option> dxOptions = EnumSet.noneOf(DxStep.Option.class);
    SmartDexingStep.createDxStepForDxPseudoRule(filesToDex, outputPath, dxOptions);
  }

  private ExecutionContext createMockedExecutionContext() {
    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getDxExecutable()).andStubReturn(new File("/usr/bin/dx"));
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.resolve(anyObject(Path.class))).andAnswer(
        new IAnswer<Path>() {
          @Override
          public Path answer() throws Throwable {
            return (Path) getCurrentArguments()[0];
          }
        }).anyTimes();
    replayAll();

    return TestExecutionContext.newBuilder()
        .setAndroidPlatformTarget(Optional.of(androidPlatformTarget))
        .setProjectFilesystem(projectFilesystem)
        .build();
  }
}
