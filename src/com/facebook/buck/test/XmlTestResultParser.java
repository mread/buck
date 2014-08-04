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

package com.facebook.buck.test;

import com.facebook.buck.java.TestOutputFormat;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

public class XmlTestResultParser {

  /** Utility Class:  Do not instantiate. */
  private XmlTestResultParser() {}

  public static TestCaseSummary parse(File xmlFile, TestOutputFormat testOutputFormat) throws IOException {
    String xmlFileContents = Files.toString(xmlFile, Charsets.UTF_8);

    final TestResultParser testResultParser;
    testResultParser = getTestResultParser(testOutputFormat);
    try {
      return testResultParser.doParse(xmlFileContents);
    } catch (NumberFormatException e) {
      // This is an attempt to track down an inexplicable error that we have observed in the wild.
      String message = createDetailedExceptionMessage(xmlFile, xmlFileContents);
      throw new RuntimeException(message, e);
    }
  }

  private static TestResultParser getTestResultParser(TestOutputFormat testOutputFormat) {
    switch (testOutputFormat) {
      case BUCK:
        return new BuckTestResultParser();
      case JUNIT:
        return new JUnitTestResultParser();
      default:
        throw new IllegalArgumentException("Unexpected TestOutputFormat: " + testOutputFormat);
    }
  }

  private static String createDetailedExceptionMessage(File xmlFile, String xmlFileContents) {
    String message = "Error parsing test result data in " + xmlFile.getAbsolutePath() + ".\n" +
        "File contents:\n" + xmlFileContents;
    return message;
  }
}
