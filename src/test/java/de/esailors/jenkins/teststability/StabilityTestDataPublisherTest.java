package de.esailors.jenkins.teststability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.PackageResult;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class StabilityTestDataPublisherTest {
	private int maxHistoryLength = 30;
	private StabilityTestDataPublisher publisher;
	private Run<?, ?> run;
	private FilePath workspace;
	private Launcher launcher;
	private TaskListener listener;

	@Before
	public void setUp() throws Exception {
		publisher = new StabilityTestDataPublisher() {
			@Override
			public DescriptorImpl getDescriptor() {
				return new DescriptorImpl() {
					@Override
					public int getMaxHistoryLength() {
						return maxHistoryLength;
					}
				};
			}
		};
		run = mock(Run.class);
		workspace = mock(FilePath.class);
		launcher = mock(Launcher.class);
		listener = mock(TaskListener.class);
		when(listener.getLogger()).thenReturn(System.out);
	}

	// TODO this only tests a small part of the method
	@Test
	public void buildHistoryForCaseResult() throws Exception {
		CaseResult result = mock(CaseResult.class);
		when(result.getId()).thenReturn("resultID");
		when(result.isPassed()).thenReturn(true);
		Map<String, CircularStabilityHistory> histories = new HashMap<String, CircularStabilityHistory>();
		int buildNumber = 5;
		publisher.buildUpInitialHistory(histories, buildNumber, singletonList(result), maxHistoryLength);
		assertThat(histories.get("resultID")).isNotNull();
		assertThat(histories.get("resultID").getData()).hasSize(1);
		assertThat(histories.get("resultID").getData()[0].buildNumber).isEqualTo(
				buildNumber);
		assertThat(histories.get("resultID").getData()[0].passed).isEqualTo(
				true);
	}

	// TODO this only tests a small part of the method
	@Test
	public void buildHistoryForClassResult() throws Exception {
		ClassResult result = mock(ClassResult.class);
		when(result.getId()).thenReturn("resultID");
		when(result.isPassed()).thenReturn(true);
		Map<String, CircularStabilityHistory> histories = new HashMap<String, CircularStabilityHistory>();
		int buildNumber = 5;
		publisher.buildUpInitialHistory(histories, buildNumber, singletonList(result), maxHistoryLength);
		assertThat(histories.get("resultID")).isNotNull();
		assertThat(histories.get("resultID").getData()).hasSize(1);
		assertThat(histories.get("resultID").getData()[0].buildNumber).isEqualTo(
				buildNumber);
		assertThat(histories.get("resultID").getData()[0].passed).isEqualTo(
				true);
	}

	@Test
	public void contributeTestData() throws Exception {

		// create a lot of results for tests which have passed (by default)
		RootInfo root = new RootInfo();
		List<PackageInfo> packages = new ArrayList<PackageInfo>();
		for (int p = 0; p < 10; p++) {
			PackageInfo pkg = new PackageInfo(root.mock, p);
			packages.add(pkg);
			List<ClassInfo> classes = new ArrayList<ClassInfo>();
			for (int c = 0; c < 10; c++) {
				ClassInfo cls = new ClassInfo(pkg.mock, c);
				classes.add(cls);
				List<CaseInfo> cases = new ArrayList<CaseInfo>();
				for (int t = 0; t < 10; t++) {
					CaseInfo caseResult = new CaseInfo(cls.mock, t);
					cases.add(caseResult);
				}
				cls.addChildren(cases);;
			}
			pkg.addChildren(classes);
		}
		root.addChildren(packages);

		// failedTest1 has passed once before, never failed before - stability 50%
		CaseInfo failedTest1 = root.pkg(2).cls(5).test(8);
		when(failedTest1.mock.getFailCount()).thenReturn(1);

		CaseResult failedTest1Previous = mock(CaseResult.class, RETURNS_DEEP_STUBS);
		when(failedTest1Previous.isPassed()).thenReturn(true);
		StabilityTestAction failedTest1PreviousStability = mock(StabilityTestAction.class);

		// NB: if previousStability.getRingBuffer() returns null, the initialHistory will be built by walking TestResult.getPrevious
		// TODO test this code path too:
//		when(failedTest1PreviousStability.getRingBuffer()).thenReturn(new CircularStabilityHistory(maxHistoryLength));

		when(failedTest1Previous.getTestAction(StabilityTestAction.class)).thenReturn(failedTest1PreviousStability);
		when(failedTest1Previous.getPreviousResult()).thenReturn(null);

		// NB this method (the non-mock one) is very slow unless TestResult for previous build is still cached in memory
		// so ideally we would test to ensure it is only called once.
		when(failedTest1.mock.getPreviousResult()).thenReturn(failedTest1Previous);
//		when(failedTest1.mock.getPreviousResult()).thenReturn(failedTest1Previous).thenThrow(new Error());

		// failedTest2 has never run before - stability 0%
		CaseInfo failedTest2 = root.pkg(3).cls(6).test(7);
		when(failedTest2.mock.getFailCount()).thenReturn(1);

		TestResultAction.Data data = publisher.contributeTestData(run,
                workspace, launcher, listener, root.mock);

		CaseInfo passingTest = root.pkg(0).cls(0).test(0);
		assertStability(data, passingTest, 100);

		assertStability(data, failedTest1, 50);
		assertStability(data, failedTest2, 0);
	}

	private void assertStability(TestResultAction.Data data,
			CaseInfo testInfo, int expectedStability) {
		List<? extends TestAction> testActions = data.getTestAction(testInfo.mock);
		assertThat(testActions).hasSize(1);
		assertThat(testActions.get(0)).isInstanceOf(StabilityTestAction.class);
		StabilityTestAction action = (StabilityTestAction) testActions.get(0);
		assertThat(action.getStability()).isEqualTo(expectedStability);
	}

	static class RootInfo {
		final TestResult mock;
		final Map<Integer, PackageInfo> packages = new HashMap<Integer, PackageInfo>();
		RootInfo() {
			mock = mock(TestResult.class, "rootTestResult");
			when(mock.getId()).thenReturn("root");
			when(mock.getName()).thenReturn("root");
		}
		void addChildren(List<PackageInfo> packages) {
			List<PackageResult> results = new ArrayList<PackageResult>();
			for (PackageInfo pkg : packages) {
				this.packages.put(pkg.num, pkg);
				results.add(pkg.mock);
			}
			when(mock.getChildren()).thenReturn(results);
		}
		PackageInfo pkg(int n) {
			return packages.get(n);
		}
	}

	static class PackageInfo {
		final PackageResult mock;
		final int num;
		final Map<Integer, ClassInfo> classes = new HashMap<Integer, ClassInfo>();
		PackageInfo(TestResult root, int p) {
			mock = mock(PackageResult.class, "packageResult"+p);
			num = p;
			when(mock.getParent()).thenReturn(root);
			when(mock.getId()).thenReturn("package"+p);
			when(mock.getName()).thenReturn("package"+p);
		}
		void addChildren(List<ClassInfo> classes) {
			List<ClassResult> results = new ArrayList<ClassResult>();
			for (ClassInfo cls : classes) {
				this.classes.put(cls.num, cls);
				results.add(cls.mock);
			}
			when(mock.getChildren()).thenReturn(results);
		}
		ClassInfo cls(int n) {
			return classes.get(n);
		}
	}

	static class ClassInfo {
		final ClassResult mock;
		final int num;
		final Map<Integer, CaseInfo> cases = new HashMap<Integer, CaseInfo>();
		ClassInfo(PackageResult pkg, int c) {
			mock = mock(ClassResult.class, "classResult"+c);
			num = c;
			when(mock.getParent()).thenReturn(pkg);
			when(mock.getId()).thenReturn("class"+c);
			when(mock.getName()).thenReturn("class"+c);
		}
		void addChildren(List<CaseInfo> cases) {
			List<CaseResult> results = new ArrayList<CaseResult>();
			for (CaseInfo p : cases) {
				this.cases.put(p.num, p);
				results.add(p.mock);
			}
			when(mock.getChildren()).thenReturn(results);
		}
		CaseInfo test(int n) {
			return cases.get(n);
		}
	}

	static class CaseInfo {
		final CaseResult mock;
		final int num;
		CaseInfo(ClassResult pkg, int c) {
			mock = mock(CaseResult.class, "caseResult"+c);
			num = c;
			when(mock.getParent()).thenReturn(pkg);
			when(mock.getId()).thenReturn("caseResult"+c);
			when(mock.getName()).thenReturn("caseResult"+c);
		}
	}

}
