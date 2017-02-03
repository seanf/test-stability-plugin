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

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import org.jvnet.localizer.Localizable;

import hudson.model.HealthReport;
import hudson.tasks.junit.TestAction;
import de.esailors.jenkins.teststability.StabilityTestData.Result;

/**
 * {@link TestAction} for the test stability history.
 * 
 * @author ckutz
 */
class StabilityTestAction extends TestAction {

	private CircularStabilityHistory ringBuffer;
	private String description;
	
	private int total;
	private int failed;
	private int testStatusChanges;
	private int stability = 100;
	private int flakiness;

	public StabilityTestAction(@CheckForNull CircularStabilityHistory ringBuffer) {
		this.ringBuffer = ringBuffer;

		if (ringBuffer != null) {
			Result[] data = ringBuffer.getData();
			this.total = data.length;
		
			computeStability(data);
			computeFlakiness(data);
		}
				
		if (this.stability == 100) {
			this.description = "No known failures. Flakiness 0%, Stability 100%";
		} else {
			this.description =
				String.format("Failed %d times in the last %d runs. Flakiness: %d%%, Stability: %d%%", failed, total, flakiness, stability);
		}
	}
	
	private void computeStability(Result[] data) {
		
		for (Result r : data) {
			if (!r.passed) {
				failed++;
			}
		}
		
		this.stability = 100 * (total - failed) / total;
	}
	
	/**
	 * Computes the flakiness in percent.
	 */
	private void computeFlakiness(Result[] data) {
		Boolean previousPassed = null;
		for (Result r : this.ringBuffer.getData()) {
			boolean thisPassed = r.passed;
			if (previousPassed != null && previousPassed != thisPassed) {
				testStatusChanges++;
			}
			previousPassed = thisPassed;
		}
		
		if (total > 1) {
			this.flakiness = 100 * testStatusChanges / (total - 1);
		} else {
			this.flakiness = 0;
		}
	}
	
	public int getFlakiness() {
		return this.flakiness;
	}
	
	public String getBigImagePath() {
		HealthReport healthReport = new HealthReport(100 - flakiness, (Localizable)null);
		return healthReport.getIconUrl("32x32");
	}
	
	public String getSmallImagePath() {
		HealthReport healthReport = new HealthReport(100 - flakiness, (Localizable)null);
		return healthReport.getIconUrl("16x16");
	}

	public CircularStabilityHistory getRingBuffer() {
		// TODO: only publish an immutable view of the buffer!
		return this.ringBuffer;
	}

	public String getDescription() {
		return this.description;
	}
	
	public @Nullable String getIconFileName() {
		return null;
	}
	
	public @Nullable String getDisplayName() {
		return null;
	}

	public @Nullable String getUrlName() {
		return null;
	}
	
}
