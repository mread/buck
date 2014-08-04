package com.facebook.buck.test;

import java.io.IOException;

public interface TestResultParser {
  TestCaseSummary doParse(String xml) throws IOException;
}
