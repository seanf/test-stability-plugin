package de.esailors.jenkins.teststability;

import org.junit.Test;

import org.junit.Assert;
import de.esailors.jenkins.teststability.StabilityTestData.Result;;

public class StabilityTestActionTest {

	@Test
	public void flakinessMustNotFailIfTotalIsOne() {
		CircularStabilityHistory ringBuffer = new CircularStabilityHistory(10);
		ringBuffer.add(new Result(1, true));
		
		StabilityTestAction action = new StabilityTestAction(ringBuffer);
		Assert.assertEquals(0, action.getFlakiness());
	}
	
	@Test
	public void flakinessMustBe100PercentWhenTestStatusChangedEachTime() {
		CircularStabilityHistory ringBuffer = new CircularStabilityHistory(10);
		
		for (int i=0; i < 10; i+=2) {
			ringBuffer.add(new Result(i, true));
			ringBuffer.add(new Result(i+1, false));
		}
		
		StabilityTestAction action = new StabilityTestAction(ringBuffer);
		Assert.assertEquals(100, action.getFlakiness());
	}
	
	@Test
	public void flakinessMustBeZeroPercentWhenTestStatusNeverChanged() {
		CircularStabilityHistory ringBuffer = new CircularStabilityHistory(10);
		
		for (int i=0; i < 10; i++) {
			ringBuffer.add(new Result(i, true));
		}
		
		StabilityTestAction action = new StabilityTestAction(ringBuffer);
		Assert.assertEquals(0, action.getFlakiness());
	}
	
	@Test
	public void flakinessMustBe50PercentWhenTestStatusChangedEverySecondTime() {
		// Use 101 elements so, we can have 50 status changes in 100 transitions
		// Otherwise we wouldn't get exactly 50 percent, but something nearby
		CircularStabilityHistory ringBuffer = new CircularStabilityHistory(101);
		
		for (int i=0; i < 105; i+=4) {
			ringBuffer.add(new Result(i, true));
			ringBuffer.add(new Result(i+1, true));
			
			ringBuffer.add(new Result(i+2, false));
			ringBuffer.add(new Result(i+3, false));
		}
		
		StabilityTestAction action = new StabilityTestAction(ringBuffer);
		Assert.assertEquals(50, action.getFlakiness());
	}
}
