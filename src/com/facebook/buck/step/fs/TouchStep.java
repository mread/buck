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

package com.facebook.buck.step.fs;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * {@link com.facebook.buck.step.Step} that runs {@code touch <filename>} in the shell.
 */
public class TouchStep extends ShellStep {

  private final Path fileToTouch;

  public TouchStep(Path fileToTouch) {
    this.fileToTouch = Preconditions.checkNotNull(fileToTouch);
  }

  @Override
  public String getShortName() {
    return "touch";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.of(
        "touch",
        context.getProjectFilesystem().resolve(fileToTouch).toString());
  }
}
