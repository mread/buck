package com.facebook.buck.junit;

import org.junit.runner.notification.Failure;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Outputs test results in native buck XML format, which is intended to be language agnostic.
 */
public class BuckResultFormatter implements TestResultFormatter {
  @Override
  public Document createResultDocument(
      String testClassName,
      List<TestResult> results) throws ParserConfigurationException {
    // XML writer logic taken from:
    // http://www.genedavis.com/library/xml/java_dom_xml_creation.jsp

    DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = docBuilder.newDocument();
    doc.setXmlVersion("1.1");

    Element root = doc.createElement("testcase");
    root.setAttribute("name", testClassName);
    doc.appendChild(root);

    for (TestResult result : results) {
      Element test = doc.createElement("test");

      // name attribute
      test.setAttribute("name", result.testMethodName);

      // success attribute
      boolean isSuccess = result.isSuccess();
      test.setAttribute("success", Boolean.toString(isSuccess));

      // type attribute
      test.setAttribute("type", result.type.toString());

      // time attribute
      long runTime = result.runTime;
      test.setAttribute("time", String.valueOf(runTime));

      // Include failure details, if appropriate.
      Failure failure = result.failure;
      if (failure != null) {
        String message = failure.getMessage();
        test.setAttribute("message", message);

        String stacktrace = failure.getTrace();
        test.setAttribute("stacktrace", stacktrace);
      }

      // stdout, if non-empty.
      if (result.stdOut != null) {
        Element stdOutEl = doc.createElement("stdout");
        stdOutEl.appendChild(doc.createTextNode(result.stdOut));
        test.appendChild(stdOutEl);
      }

      // stderr, if non-empty.
      if (result.stdErr != null) {
        Element stdErrEl = doc.createElement("stderr");
        stdErrEl.appendChild(doc.createTextNode(result.stdErr));
        test.appendChild(stdErrEl);
      }

      root.appendChild(test);
    }
    return doc;
  }
}
