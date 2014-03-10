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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.FileSourcePath;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

/**
 * Unit tests for {@link GroupedSources}.
 */
public class GroupedSourcesTest {
  @Test
  public void testSourcePathsForGroupedSources() {
    ImmutableList<GroupedSource> groupedSources = ImmutableList.of(
        GroupedSource.ofSourceGroup(
            "Group1",
            ImmutableList.of(
                GroupedSource.ofSourcePath(new FileSourcePath("foo.m")),
                GroupedSource.ofSourcePath(new FileSourcePath("bar.m")))),
        GroupedSource.ofSourceGroup(
            "Group2",
            ImmutableList.of(
                GroupedSource.ofSourcePath(new FileSourcePath("baz.m")),
                GroupedSource.ofSourcePath(new FileSourcePath("blech.m")))));
    assertEquals(
        ImmutableList.of(
            new FileSourcePath("foo.m"),
            new FileSourcePath("bar.m"),
            new FileSourcePath("baz.m"),
            new FileSourcePath("blech.m")),
        GroupedSources.sourcePaths(groupedSources));
  }
}
