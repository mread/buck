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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WriteFileStep implements Step {

  private final Supplier<String> content;
  private final Path outputPath;

  public WriteFileStep(String content, String outputPath) {
    this(Suppliers.ofInstance(content), Paths.get(outputPath));
  }

  public WriteFileStep(String content, Path outputPath) {
    this(Suppliers.ofInstance(content), outputPath);
  }

  public WriteFileStep(Supplier<String> content, String outputPath) {
    this(content, Paths.get(outputPath));
  }

  public WriteFileStep(Supplier<String> content, Path outputPath) {
    this.content = Preconditions.checkNotNull(content);
    this.outputPath = Preconditions.checkNotNull(outputPath);
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      // echo by default writes a trailing new line and so should we.
      Files.write(content.get() + "\n",
          context.getProjectFilesystem().getFileForRelativePath(outputPath),
          Charsets.UTF_8);
      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  @Override
  public String getShortName() {
    return "write_file";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        "echo %s > %s",
        Escaper.escapeAsBashString(content.get()),
        Escaper.escapeAsBashString(outputPath));
  }

}
