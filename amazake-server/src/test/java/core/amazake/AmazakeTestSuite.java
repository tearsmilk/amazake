package core.amazake;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Amazake's own unit tests (the server's test task only runs *TestSuite classes). */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Amazake unit tests")
@SelectPackages("core.amazake")
public class AmazakeTestSuite {
}
