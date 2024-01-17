/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;

import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.schedule.VmScheduleResource;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.mockito.Mockito;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link VmResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class VmResourceTest extends AbstractServerTest {

	@Autowired
	private VmResource resource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	private VmExecutionRepository vmExecutionRepository;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class<?>[]{Node.class, Project.class, Subscription.class, VmSchedule.class},
				StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter");
	}

	@AfterEach
	void cleanTrigger() throws SchedulerException {

		// Remove all previous VM trigger
		final var scheduler = vmSchedulerFactoryBean.getScheduler();
		scheduler.unscheduleJobs(new ArrayList<>(
				scheduler.getTriggerKeys(GroupMatcher.groupEquals(VmScheduleResource.SCHEDULE_TRIGGER_GROUP))));
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, VmResource.SERVICE_KEY);
	}

	@Test
	void delete() throws SchedulerException {
		final var project = new Project();
		project.setName("TEST");
		project.setPkey("test");
		em.persist(project);

		final var subscription = new Subscription();
		subscription.setProject(project);
		subscription.setNode(nodeRepository.findOneExpected("service:vm"));
		em.persist(subscription);

		Assertions.assertEquals(1, subscriptionRepository.findAllByProject(project.getId()).size());
		em.flush();
		em.clear();

		resource.delete(subscription.getId(), false);
		subscriptionRepository.delete(subscription);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, subscriptionRepository.findAllByProject(project.getId()).size());
		Assertions.assertEquals(0, vmScheduleRepository.findBySubscription(subscription.getId()).size());
		Assertions.assertEquals(0, vmExecutionRepository.findAllBy("subscription.id", subscription.getId()).size());
	}

	@Test
	void getConfiguration() throws ParseException {
		final var resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);

		final var configuration = resource.getConfiguration(subscription);
		final var schedules = configuration.getSchedules();
		Assertions.assertEquals(1, schedules.size());
		Assertions.assertEquals("0 0 0 1 1 ? 2050", schedules.getFirst().getCron());
		Assertions.assertEquals(getDate(2050, 1, 1, 0, 0, 0), schedules.getFirst().getNext());
		Assertions.assertNotNull(schedules.getFirst().getId());
		Assertions.assertEquals(VmOperation.OFF, schedules.getFirst().getOperation());
		Assertions.assertFalse(configuration.isSupportSnapshot());

		// Coverage only
		Assertions.assertEquals(VmOperation.OFF,
				VmOperation.values()[VmOperation.valueOf(VmOperation.OFF.name()).ordinal()]);
	}

	@Test
	void getConfigurationSupportSnapshot() throws ParseException {
		final var resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Mockito.doReturn(Mockito.mock(Snapshotting.class)).when(resource.locator).getResource("service:vm:test:test",
				Snapshotting.class);

		Assertions.assertTrue(resource.getConfiguration(subscription).isSupportSnapshot());
	}

	@Test
	void getKey() {
		Assertions.assertEquals("service:vm", resource.getKey());
	}
}
