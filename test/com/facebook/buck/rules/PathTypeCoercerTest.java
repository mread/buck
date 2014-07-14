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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.util.ProjectFilesystem;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PathTypeCoercerTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private final BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
  private ProjectFilesystem filesystem;
  private final Path pathRelativeToProjectRoot = Paths.get("");
  private final PathTypeCoercer pathTypeCoercer = new PathTypeCoercer();

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmpDir.getRoot());
  }

  @Test
  public void coercingInvalidPathThrowsException() {
    String invalidPath = "";
    try {
      pathTypeCoercer.coerce(
          buildRuleResolver,
          filesystem,
          pathRelativeToProjectRoot,
          invalidPath);
      fail("expected to throw when");
    } catch (CoerceFailedException e) {
      assertEquals("invalid path", e.getMessage());
    }
  }

  @Test
  public void coercingMissingFileThrowsException() {
    String missingPath = "hello";
    try {
      pathTypeCoercer.coerce(
          buildRuleResolver,
          filesystem,
          pathRelativeToProjectRoot,
          missingPath);
      fail("expected to throw");
    } catch (CoerceFailedException e) {
      assertEquals(
          String.format("no such file or directory '%s'", missingPath),
          e.getMessage());
    }
  }

}
