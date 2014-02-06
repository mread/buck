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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.MergeAndroidResourcesStep.Resource;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedSetMultimap;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

public class MergeAndroidResourcesStepTest {
  @Test
  public void testGenerateRDotJavaForMultipleSymbolsFiles() throws IOException {
    RDotTxtEntryBuilder entriesBuilder = new RDotTxtEntryBuilder();

    // Merge everything into the same package space.
    String sharedPackageName = "com.facebook.abc";
    entriesBuilder.add(new RDotTxtEntry(sharedPackageName, "a-R.txt",
        "int id a1 0x7f010001\n" +
        "int id a2 0x7f010002\n" +
        "int string a1 0x7f020001\n"));

    entriesBuilder.add(new RDotTxtEntry(sharedPackageName, "b-R.txt",
        "int id b1 0x7f010001\n" +
        "int id b2 0x7f010002\n"));

    entriesBuilder.add(new RDotTxtEntry(sharedPackageName, "c-R.txt",
        "int attr c1 0x7f010001\n" +
        "int[] styleable c1 { 0x7f010001 }\n"));

    SortedSetMultimap<String, Resource> packageNameToResources =
        MergeAndroidResourcesStep.sortSymbols(
            entriesBuilder.buildReadableMapper(),
            entriesBuilder.buildFilePathToPackageNameSet(),
            true /* reenumerate */);

    assertEquals(1, packageNameToResources.keySet().size());
    SortedSet<Resource> resources = packageNameToResources.get(sharedPackageName);
    assertEquals(7, resources.size());

    Set<String> uniqueEntries = Sets.newHashSet();
    for (Resource resource : resources) {
      if (!resource.type.equals("styleable")) {
        assertFalse("Duplicate ids should be fixed by renumerate=true; duplicate was: " +
            resource.idValueToWrite, uniqueEntries.contains(resource.idValueToWrite));
        uniqueEntries.add(resource.idValueToWrite);
      }
    }

    assertEquals(6, uniqueEntries.size());

    // All good, no need to further test whether we can write the Java file correctly...
  }

  @Test
  public void testGenerateRDotJavaForOneSymbolsFile() {
    String symbolsFile = BuckConstant.BIN_DIR +
        "/android_res/com/facebook/http/__res_resources_text_symbols__/R.txt";
    String rDotJavaPackage = "com.facebook";
    final String outputTextSymbols =
        "int id placeholder 0x7f020000\n" +
        "int string debug_http_proxy_dialog_title 0x7f030004\n" +
        "int string debug_http_proxy_hint 0x7f030005\n" +
        "int string debug_http_proxy_summary 0x7f030003\n" +
        "int string debug_http_proxy_title 0x7f030002\n" +
        "int string debug_ssl_cert_check_summary 0x7f030001\n" +
        "int string debug_ssl_cert_check_title 0x7f030000\n" +
        "int styleable SherlockMenuItem_android_visible 4\n" +
        "int[] styleable SherlockMenuView { 0x7f010026, 0x7f010027, 0x7f010028, 0x7f010029, 0x7f01002a, 0x7f01002b, 0x7f01002c, 0x7f01002d }\n";
    RDotTxtEntryBuilder entriesBuilder = new RDotTxtEntryBuilder();
    entriesBuilder.add(new RDotTxtEntry(rDotJavaPackage, symbolsFile, outputTextSymbols));
    SortedSetMultimap<String, Resource> rDotJavaPackageToResources =
        MergeAndroidResourcesStep.sortSymbols(
            entriesBuilder.buildReadableMapper(),
            entriesBuilder.buildFilePathToPackageNameSet(),
            false /* reenumerate */);

    assertEquals(1, rDotJavaPackageToResources.keySet().size());

    // Verify all of the values in the resources collection.
    SortedSet<Resource> resources = rDotJavaPackageToResources.get(rDotJavaPackage);
    assertEquals(9, resources.size());
    Iterator<Resource> iter = resources.iterator();
    assertResource("int", "id", "placeholder", "0x7f020000", iter.next());
    assertResource("int", "string", "debug_http_proxy_dialog_title", "0x7f030004", iter.next());
    assertResource("int", "string", "debug_http_proxy_hint", "0x7f030005", iter.next());
    assertResource("int", "string", "debug_http_proxy_summary", "0x7f030003", iter.next());
    assertResource("int", "string", "debug_http_proxy_title", "0x7f030002", iter.next());
    assertResource("int", "string", "debug_ssl_cert_check_summary", "0x7f030001", iter.next());
    assertResource("int", "string", "debug_ssl_cert_check_title", "0x7f030000", iter.next());
    assertResource("int", "styleable", "SherlockMenuItem_android_visible", "4", iter.next());
    assertResource("int[]",
        "styleable",
        "SherlockMenuView",
        "{ 0x7f010026, 0x7f010027, 0x7f010028, 0x7f010029, 0x7f01002a, 0x7f01002b, 0x7f01002c, 0x7f01002d }",
        iter.next());

    // Verify that the correct Java code is generated.
    String javaCode = MergeAndroidResourcesStep.generateJavaCodeForPackageAndResources(
        rDotJavaPackage, resources);
    assertEquals(
        "package com.facebook;\n" +
        "\n" +
        "public class R {\n" +
        "\n" +
        "  public static class id {\n" +
        "    public static int placeholder=0x7f020000;\n" +
        "  }\n" +
        "\n" +
        "  public static class string {\n" +
        "    public static int debug_http_proxy_dialog_title=0x7f030004;\n" +
        "    public static int debug_http_proxy_hint=0x7f030005;\n" +
        "    public static int debug_http_proxy_summary=0x7f030003;\n" +
        "    public static int debug_http_proxy_title=0x7f030002;\n" +
        "    public static int debug_ssl_cert_check_summary=0x7f030001;\n" +
        "    public static int debug_ssl_cert_check_title=0x7f030000;\n" +
        "  }\n" +
        "\n" +
        "  public static class styleable {\n" +
        "    public static int SherlockMenuItem_android_visible=4;\n" +
        "    public static int[] SherlockMenuView={ 0x7f010026, 0x7f010027, 0x7f010028, 0x7f010029, 0x7f01002a, 0x7f01002b, 0x7f01002c, 0x7f01002d };\n" +
        "  }\n" +
        "\n" +
        "}\n",
        javaCode);
  }

  @Test
  public void testGenerateRDotJavaForEmptyResources() {
    String packageName = "com.facebook";
    String rDotJava = MergeAndroidResourcesStep.generateJavaCodeForPackageWithoutResources(
        packageName);
    assertEquals(
        "package com.facebook;\n" +
        "\n" +
        "public class R {\n" +
        "\n" +
        "}\n",
        rDotJava);
  }

  /**
   * A special comparison for two {@link Resource} objects because {@link Resource#equals(Object)}
   * does not compare all of the fields.
   */
  private void assertResource(
      String expectedIdType,
      String expectedType,
      String expectedName,
      String expectedIdValue,
      Resource observedResource) {
    assertEquals(expectedIdType, observedResource.idType);
    assertEquals(expectedType, observedResource.type);
    assertEquals(expectedName, observedResource.name);
    assertEquals(expectedIdValue, observedResource.originalIdValue);
  }

  // sortSymbols has a goofy API.  This will help.
  private static class RDotTxtEntryBuilder {
    private final ImmutableMap.Builder<Path, String> filePathToContents =
        ImmutableMap.builder();
    private final ImmutableMap.Builder<Path, String> filePathToPackageName =
        ImmutableMap.builder();

    public RDotTxtEntryBuilder() {
    }

    public void add(RDotTxtEntry entry) {
      filePathToContents.put(entry.filePath, entry.contents);
      filePathToPackageName.put(entry.filePath, entry.packageName);
    }

    public Function<Path, Readable> buildReadableMapper() {
      final ImmutableMap<Path, String> builtInstance = filePathToContents.build();
      return new Function<Path, Readable>() {
        @Override
        public Readable apply(Path filePath) {
          String content = builtInstance.get(filePath);
          if (content == null) {
            throw new RuntimeException("No content for " + filePath);
          } else {
            return new StringReader(content);
          }
        }
      };
    }

    public Map<Path, String> buildFilePathToPackageNameSet() {
      return filePathToPackageName.build();
    }
  }

  private static class RDotTxtEntry {
    public String packageName;
    public Path filePath;
    public String contents;

    public RDotTxtEntry(String packageName, String filePath, String contents) {
      this.packageName = packageName;
      this.filePath = Paths.get(filePath);
      this.contents = contents;
    }
  }
}
