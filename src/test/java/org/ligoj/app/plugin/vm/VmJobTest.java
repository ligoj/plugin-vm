package org.ligoj.app.plugin.vm;

import org.junit.Assert;
import org.junit.Test;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.quartz.TriggerKey;

/**
 * Test class of {@link VmJob}
 */
public class VmJobTest {

	@Test
	public void getSubscription() {
		Assert.assertEquals(10, VmJob.getSubscription(new TriggerKey("10-any")));
		Assert.assertEquals(1000, VmJob.getSubscription(new TriggerKey("1000-any")));
	}

	@Test
	public void getOperation() {
		Assert.assertEquals(VmOperation.OFF, VmJob.getOperation(new TriggerKey("10-OFF")));
		Assert.assertEquals(VmOperation.OFF, VmJob.getOperation(new TriggerKey("1000-OFF")));
	}

	@Test
	public void parse() {
		Assert.assertArrayEquals(new Object[2], VmJob.parse("any-OFF"));
	}

	@Test
	public void format() {
		Assert.assertEquals("12345-OFF", VmJob.format(12345, VmOperation.OFF));
	}
}
