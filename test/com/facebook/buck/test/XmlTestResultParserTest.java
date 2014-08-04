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

package com.facebook.buck.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.java.TestOutputFormat;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class XmlTestResultParserTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testParseMalformedXml() throws IOException {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n" +
        "<testcase name='com.facebook.buck.test.XmlTestResultParserTest'>\n" +
        "  <test name='testParseMalformedXml' success='true' time='too meta'/>\n" +
        "</testcase>\n";
    File xmlFile = tmp.newFile("result.xml");
    Files.write(xml, xmlFile, Charsets.UTF_8);

    try {
      XmlTestResultParser.parse(xmlFile, TestOutputFormat.BUCK);
      fail("Should throw RuntimeException.");
    } catch (RuntimeException e) {
      assertTrue("The RuntimeException should wrap the NumberFormatException.",
          e.getCause() instanceof NumberFormatException);

      assertEquals(
          "Exception should include the path to the file as well as its contents.",
          "Error parsing test result data in " + xmlFile.getAbsolutePath() + ".\n" +
              "File contents:\n" + xml,
          e.getMessage());
    }
  }
}
