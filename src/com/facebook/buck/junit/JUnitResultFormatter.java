package com.facebook.buck.junit;

import com.facebook.buck.test.result.type.ResultType;

import org.junit.runner.notification.Failure;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Outputs test results in JUnit XML format, for integration with JUnit compatible tools like
 * jenkins.
 */
public class JUnitResultFormatter implements TestResultFormatter {
  @Override
  public Document createResultDocument(
      String testClassName,
      List<TestResult> results) throws ParserConfigurationException {
    // XML writer logic taken from:
    // http://www.genedavis.com/library/xml/java_dom_xml_creation.jsp

    DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = docBuilder.newDocument();
    doc.setXmlVersion("1.1");

    Element root = doc.createElement("testsuite");
    root.setAttribute("name", testClassName);

    int failureCount = 0;
    int skipCount = 0;
    int testCount = results.size();
    double runtime = 0;

    for (TestResult result : results) {
      if (result.type == ResultType.FAILURE) {
        ++failureCount;
      } else if (result.type == ResultType.ASSUMPTION_VIOLATION) {
        ++skipCount;
      }
      runtime += result.runTime;
    }

    // how do errors manifest themselves?
    root.setAttribute("errors", "0");
    root.setAttribute("failures", String.valueOf(failureCount));
    root.setAttribute("skipped", String.valueOf(skipCount));
    root.setAttribute("tests", String.valueOf(testCount));
    root.setAttribute("time", String.format("%.3f", runtime / 1000));
    doc.appendChild(root);

    doc.createElement("properties");

    for (TestResult result : results) {
      Element test = doc.createElement("testcase");

      // classname attribute
      test.setAttribute("classname", result.testClassName);

      // name attribute
      test.setAttribute("name", result.testMethodName);

      // success attribute
      boolean isSuccess = result.isSuccess();
      test.setAttribute("success", Boolean.toString(isSuccess));

      // type attribute
      test.setAttribute("type", result.type.toString());

      // time attribute
      runtime = result.runTime;
      test.setAttribute("time", String.format("%.3f", runtime / 1000));

      // Include failure details, if appropriate.
      Failure failure = result.failure;
      if (failure != null) {
        final Element failureElement = doc.createElement("failure");
        failureElement.setAttribute("message", failure.getMessage());
        failureElement.setAttribute("type", failure.getException().getClass().getSimpleName());
        failureElement.appendChild(doc.createTextNode(failure.getTrace()));
        test.appendChild(failureElement);
      }

      // stdout, if non-empty.
      if (result.stdOut != null) {
        Element stdOutEl = doc.createElement("system-out");
        stdOutEl.appendChild(doc.createTextNode(result.stdOut));
        test.appendChild(stdOutEl);
      }

      // stderr, if non-empty.
      if (result.stdErr != null) {
        Element stdErrEl = doc.createElement("system-err");
        stdErrEl.appendChild(doc.createTextNode(result.stdErr));
        test.appendChild(stdErrEl);
      }

      root.appendChild(test);
    }
    return doc;
  }
}
