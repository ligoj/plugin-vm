/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.schedule;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.quartz.TriggerKey;

/**
 * Test class of {@link VmJob}
 */
class VmJobTest {

	@Test
	void getSubscription() {
		Assertions.assertEquals(999, VmJob.getSubscription(new TriggerKey("10-999")));
	}

	@Test
	void getSchedule() {
		Assertions.assertEquals(10, VmJob.getSchedule(new TriggerKey("10-999")));
	}

	@Test
	void parse() {
		Assertions.assertArrayEquals(new Object[2], VmJob.parse("any-OFF"));
	}

	@Test
	void format() {
		final var vmSchedule = new VmSchedule();
		vmSchedule.setId(6789);
		final var subscription = new Subscription();
		subscription.setId(12345);
		vmSchedule.setSubscription(subscription);
		Assertions.assertEquals("6789-12345", VmJob.format(vmSchedule));
	}
}
