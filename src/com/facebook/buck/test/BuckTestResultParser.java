package com.facebook.buck.test;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

public class BuckTestResultParser implements TestResultParser {
  public TestCaseSummary doParse(String xml) throws IOException {
    Document doc = XmlDomParser.parse(
        new InputSource(new StringReader(xml)),
      /* namespaceAware */ true);
    Element root = doc.getDocumentElement();
    Preconditions.checkState("testcase".equals(root.getTagName()));
    String testCaseName = root.getAttribute("name");

    NodeList testElements = doc.getElementsByTagName("test");
    List<TestResultSummary> testResults = Lists.newArrayListWithCapacity(testElements.getLength());
    for (int i = 0; i < testElements.getLength(); i++) {
      Element node = (Element) testElements.item(i);
      String testName = node.getAttribute("name");
      long time = Long.parseLong(node.getAttribute("time"));
      String typeString = node.getAttribute("type");
      ResultType type = ResultType.valueOf(typeString);

      String message;
      String stacktrace;
      if (type == ResultType.SUCCESS) {
        message = null;
        stacktrace = null;
      } else {
        message = node.getAttribute("message");
        stacktrace = node.getAttribute("stacktrace");
      }

      NodeList stdoutElements = node.getElementsByTagName("stdout");
      String stdOut;
      if (stdoutElements.getLength() == 1) {
        stdOut = stdoutElements.item(0).getTextContent();
      } else {
        stdOut = null;
      }

      NodeList stderrElements = node.getElementsByTagName("stderr");
      String stdErr;
      if (stderrElements.getLength() == 1) {
        stdErr = stderrElements.item(0).getTextContent();
      } else {
        stdErr = null;
      }

      TestResultSummary testResult = new TestResultSummary(
          testCaseName,
          testName,
          type,
          time,
          message,
          stacktrace,
          stdOut,
          stdErr);
      testResults.add(testResult);
    }

    return new TestCaseSummary(testCaseName, testResults);
  }
}
