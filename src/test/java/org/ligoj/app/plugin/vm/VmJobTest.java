package org.ligoj.app.plugin.vm;

import org.junit.Assert;
import org.junit.Test;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.quartz.TriggerKey;

/**
 * Test class of {@link VmJob}
 */
public class VmJobTest {

	@Test
	public void getSubscription() {
		Assert.assertEquals(999, VmJob.getSubscription(new TriggerKey("10-999")));
	}

	@Test
	public void getSchedule() {
		Assert.assertEquals(10, VmJob.getSchedule(new TriggerKey("10-999")));
	}

	@Test
	public void parse() {
		Assert.assertArrayEquals(new Object[2], VmJob.parse("any-OFF"));
	}

	@Test
	public void format() {
		final VmSchedule vmSchedule = new VmSchedule();
		vmSchedule.setId(6789);
		final Subscription subscription = new Subscription();
		subscription.setId(12345);
		vmSchedule.setSubscription(subscription);
		Assert.assertEquals("6789-12345", VmJob.format(vmSchedule));
	}
}
