package com.facebook.buck.junit;

import org.w3c.dom.Document;

import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

public interface TestResultFormatter {
  Document createResultDocument(
      String testClassName,
      List<TestResult> results) throws ParserConfigurationException;
}
