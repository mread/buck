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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Superclass for file, directories, and groups. Xcode's virtual file hierarchy are made of these
 * objects.
 */
public class PBXReference extends PBXContainerItem {
  public enum SourceTree {
    /**
     * Relative to the path of the group containing this.
     */
    GROUP("<group>"),

    /**
     * Absolute system path.
     */
    ABSOLUTE("<absolute>"),
    /**
     * Relative to the build setting {@code BUILT_PRODUCTS_DIR}.
     */
    BUILT_PRODUCTS_DIR("BUILT_PRODUCTS_DIR"),

    /**
     * Relative to the build setting {@code SDKROOT}.
     */
    SDKROOT("SDKROOT"),
    ;

    private final String rep;
    SourceTree(String str) {
      rep = str;
    }

    public String toString() {
      return rep;
    }
  }

  private final String name;
  @Nullable private String path;

  /**
   * The "base" path of the reference. The absolute path is resolved by prepending the resolved
   * base path.
   */
  private SourceTree sourceTree;

  public PBXReference(String name, @Nullable String path, SourceTree sourceTree) {
    this.name = Preconditions.checkNotNull(name);
    this.path = path;
    this.sourceTree = Preconditions.checkNotNull(sourceTree);
  }

  public String getName() {
    return name;
  }
  public String getPath() {
    return path;
  }
  public void setPath(String v) {
    path = v;
  }
  public SourceTree getSourceTree() {
    return sourceTree;
  }
  public void setSourceTree(SourceTree v) {
    sourceTree = v;
  }

  @Override
  public String isa() {
    return "PBXReference";
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    s.addField("name", name);
    s.addField("path", path);
    s.addField("sourceTree", sourceTree.toString());
  }
}
