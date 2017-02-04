/*
 * The MIT License
 * 
 * Copyright (c) 2013, eSailors IT Solutions GmbH
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.esailors.jenkins.teststability;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.junit.PackageResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction.Data;
import hudson.tasks.junit.ClassResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import de.esailors.jenkins.teststability.StabilityTestData.Result;

/**
 * {@link TestDataPublisher} for the test stability history.
 * 
 * @author ckutz
 */
public class StabilityTestDataPublisher extends TestDataPublisher {
	
//	public static final boolean DEBUG = false;
	public static final boolean DEBUG = true;
	
	@DataBoundConstructor
	public StabilityTestDataPublisher() {
	}
	
	@Override
	public Data contributeTestData(Run<?, ?> run, @Nonnull FilePath workspace, Launcher launcher, TaskListener listener,
								   TestResult testResult) throws IOException, InterruptedException {

		// keyed by TestResult ID
		Map<String,CircularStabilityHistory> stabilityHistoryPerTest = new HashMap<String,CircularStabilityHistory>();
		
		Collection<hudson.tasks.test.TestResult> classAndCaseResults = getClassAndCaseResults(testResult);
		if (DEBUG) {
			debug("Found " + classAndCaseResults.size() + " test results",
					listener);
		}
		Collection<hudson.tasks.test.TestResult> resultsWithNoHistory = new ArrayList<hudson.tasks.test.TestResult>();
		int runNumber = run.getNumber();
		for (hudson.tasks.test.TestResult result: classAndCaseResults) {
			
			// This is the first place we call TestResult.getPreviousResult
			CircularStabilityHistory history = getPreviousHistory(result);
			
			if (history != null) {
				if (result.isPassed()) {
					history.add(runNumber, true);
					
					if (history.isAllPassed()) {
						history = null;
					}
					
				} else if (result.getFailCount() > 0) {
					history.add(runNumber, false);
				}
				// else test is skipped and we leave history unchanged
				
				if (history != null) {
					stabilityHistoryPerTest.put(result.getId(), history);
				} else {
					stabilityHistoryPerTest.remove(result.getId());
				}
			} else if (isFirstTestFailure(result, history)) {
				if (DEBUG) {
					debug("Found failed test " + result.getId(), listener);
				}
				// TODO should we gather history for any result which lacks it, pass or fail?
				resultsWithNoHistory.add(result);
			}
		}
		int maxHistoryLength = getDescriptor().getMaxHistoryLength();
		if (!resultsWithNoHistory.isEmpty()) {
			buildUpInitialHistory(stabilityHistoryPerTest, runNumber,
					resultsWithNoHistory, maxHistoryLength);
		}
		
		return new StabilityTestData(stabilityHistoryPerTest);
	}

	private void debug(String msg, TaskListener listener) {
		if (DEBUG) {
			listener.getLogger().println(msg);
		}
	}

	private @Nullable CircularStabilityHistory getPreviousHistory(hudson.tasks.test.TestResult result) {
		hudson.tasks.test.TestResult previous = getPreviousResult(result);

		if (previous != null) {
			StabilityTestAction previousAction = previous.getTestAction(StabilityTestAction.class);
			if (previousAction != null) {
				CircularStabilityHistory prevHistory = previousAction.getRingBuffer();
				
				if (prevHistory == null) {
					return null;
				}
				
				// copy to new to not modify the old data
				CircularStabilityHistory newHistory = new CircularStabilityHistory(getDescriptor().getMaxHistoryLength());
				newHistory.addAll(prevHistory.getData());
				return newHistory;
			}
		}
		return null;
	}

	/**
	 * Is this a failed test with no history recorded yet?
	 */
	private boolean isFirstTestFailure(hudson.tasks.test.TestResult result,
			CircularStabilityHistory previousRingBuffer) {
		return previousRingBuffer == null && result.getFailCount() > 0;
	}

	/**
	 * Build up the initial histories for a collection of (failing) TestResults
	 */
	void buildUpInitialHistory(
			// keyed by TestResult ID
			Map<String, CircularStabilityHistory> stabilityHistoryPerTest,
			int runNumber,
			Collection<? extends hudson.tasks.test.TestResult> failingResults,
			int maxHistoryLength) {
		for (hudson.tasks.test.TestResult result: failingResults) {
			CircularStabilityHistory ringBuffer = new CircularStabilityHistory(maxHistoryLength);

			// add previous results (if there are any):
			List<Result> testResultsFromNewestToOldest = new ArrayList<Result>(
					maxHistoryLength - 1);
			// This is the second place we call TestResult.getPreviousResult
			hudson.tasks.test.TestResult previousResult = getPreviousResult(result);
			while (previousResult != null) {
                testResultsFromNewestToOldest.add(
                        new Result(previousResult.getRun().getNumber(), previousResult.isPassed()));
                previousResult = previousResult.getPreviousResult();
            }

			for (int i = testResultsFromNewestToOldest.size() - 1; i >= 0; i--) {
                ringBuffer.add(testResultsFromNewestToOldest.get(i));
            }

            // in case we start collecting history for passing tests too:
			boolean passed = result.isPassed();
			ringBuffer.add(runNumber, passed);
			stabilityHistoryPerTest.put(result.getId(), ringBuffer);
		}
	}
	
	/**
	 * Gets the previous result for any test result (case, class, package or build)
	 * @param result the previous result
	 * @return null if no previous result, or in case of error
	 */
	private @Nullable hudson.tasks.test.TestResult getPreviousResult(hudson.tasks.test.TestResult result) {
		try {
			return result.getPreviousResult();
		} catch (RuntimeException e) {
			// there's a bug (only on freestyle builds!) that getPreviousResult may throw a NPE (only for ClassResults!) in Jenkins 1.480
			// Note: doesn't seem to occur anymore in Jenkins 1.520
			// Don't know about the versions between 1.480 and 1.520
			
			
			// TODO: Untested:
//			if (result instanceof ClassResult) {
//				ClassResult cr = (ClassResult) result;
//				PackageResult pkgResult = cr.getParent();
//				hudson.tasks.test.TestResult topLevelPrevious = pkgResult.getParent().getPreviousResult();
//				if (topLevelPrevious != null) {
//					if (topLevelPrevious instanceof TestResult) {
//						TestResult junitTestResult = (TestResult) topLevelPrevious;
//						PackageResult prvPkgResult = junitTestResult.byPackage(pkgResult.getName());
//						if (pkgResult != null) {
//							return pkgResult.getClassResult(cr.getName());
//						}
//					}
//				}
//					
//			}
			
			return null;
		}
	}

	/**
	 * Collects all the CaseResults and ClassResults nested inside an entire build's TestResult
	 * @param testResult the build's TestResult
	 * @return a collection of the test class results and test case results in the entire build
	 */
	private Collection<hudson.tasks.test.TestResult> getClassAndCaseResults(TestResult testResult) {
		List<hudson.tasks.test.TestResult> results = new ArrayList<hudson.tasks.test.TestResult>();
		
		Collection<PackageResult> packageResults = testResult.getChildren();
		for (PackageResult pkgResult : packageResults) {
			Collection<ClassResult> classResults = pkgResult.getChildren();
			for (ClassResult cr : classResults) {
				results.add(cr);
				results.addAll(cr.getChildren());
			}
		}

		return results;
	}

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }
	

	@Extension
	public static class DescriptorImpl extends Descriptor<TestDataPublisher> {
		
		private int maxHistoryLength = 30;

		@Override
		public boolean configure(StaplerRequest req, JSONObject json)
				throws FormException {
			this.maxHistoryLength = json.getInt("maxHistoryLength");
			
			save();
            return super.configure(req,json);
		}

		/**
		 * Maximum number of history items (from configuration)
		 * @return number of items
		 */
		public int getMaxHistoryLength() {
			return this.maxHistoryLength;
		}

		@Override
		public String getDisplayName() {
			return "Test stability history";
		}
	}
}
