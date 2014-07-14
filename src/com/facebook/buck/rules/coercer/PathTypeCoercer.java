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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PathTypeCoercer extends LeafTypeCoercer<Path> {

  @Override
  public Class<Path> getOutputClass() {
    return Path.class;
  }

  @Override
  public Path coerce(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof String) {
      String path = (String) object;
      if (path.startsWith(BuildRuleFactoryParams.GENFILE_PREFIX)) {
        path = path.substring(BuildRuleFactoryParams.GENFILE_PREFIX.length());

        // A genfile is created during the build, so it's legit that it might not exist.
        return Paths.get(BuckConstant.GEN_DIR)
            .resolve(pathRelativeToProjectRoot)
            .resolve(path)
            .normalize();
      } else {
        final Path normalizedPath;

        // Normalize the path.
        try {
          normalizedPath = pathRelativeToProjectRoot.resolve(path).normalize();
        } catch (RuntimeException e) {
          throw new CoerceFailedException("invalid path", e);
        }

        // Verify that the path exists
        try {
          filesystem.getPathForRelativeExistingPath(normalizedPath);
        } catch (RuntimeException e) {
          throw new CoerceFailedException(
              String.format("no such file or directory '%s'", normalizedPath),
              e);
        }

        return normalizedPath;
      }
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}
