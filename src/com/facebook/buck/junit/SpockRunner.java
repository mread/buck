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

package com.facebook.buck.junit;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestDescription;

import java.util.ArrayList;
import java.util.List;

public class SpockRunner extends BaseRunner {
  @Override
  public void run() throws Throwable {
    System.out.println("SpockRunner started!");

    for (String className : testClassNames) {

      System.out.println(className);

      List<TestResult> results = new ArrayList<>();
      for (TestDescription seenDescription : seenDescriptions) {

        System.err.println(seenDescription);

        if (seenDescription.getClassName().equals(className)) {
          TestResult fakeResult = new TestResult(
              seenDescription.getClassName(),
              seenDescription.getMethodName(),
              0L,
              ResultType.DRY_RUN,
              null,
              "",
              "");
          results.add(fakeResult);
        }
      }

      writeResult(className, results);
    }
  }
}
