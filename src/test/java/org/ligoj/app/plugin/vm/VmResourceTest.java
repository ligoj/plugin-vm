package org.ligoj.app.plugin.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.transaction.Transactional;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.SpringUtils;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link VmResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class VmResourceTest extends AbstractServerTest {

	@Autowired
	private VmResource resource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	private ServicePluginLocator servicePluginLocator;

	protected int subscription;

	private VmServicePlugin mockVmTool;

	private ServicePluginLocator mockServicePluginLocator;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	@Before
	public void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class[] { Node.class, Project.class, Subscription.class, VmSchedule.class },
				StandardCharsets.UTF_8.name());

		this.subscription = getSubscription("gStack");

		mockServicePluginLocator = Mockito.mock(ServicePluginLocator.class);
		mockVmTool = Mockito.mock(VmServicePlugin.class);
		Mockito.when(mockServicePluginLocator.getResource(ArgumentMatchers.anyString())).then(invocation -> {
			final String resource = (String) invocation.getArguments()[0];
			if (resource.equals("service:vm:test:test")) {
				return mockVmTool;
			}
			return VmResourceTest.this.servicePluginLocator.getResource(resource);
		});
		Mockito.when(mockServicePluginLocator.getResourceExpected(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
				.then(invocation -> {
					final String resource = (String) invocation.getArguments()[0];
					if (resource.equals("service:vm:test:test")) {
						return mockVmTool;
					}
					return VmResourceTest.this.servicePluginLocator.getResourceExpected(resource,
							(Class<?>) invocation.getArguments()[1]);
				});
		final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
		SpringUtils.setSharedApplicationContext(applicationContext);
		Mockito.when(applicationContext.getBean(ServicePluginLocator.class)).thenReturn(mockServicePluginLocator);

	}

	@After
	public void cleanTrigger() throws SchedulerException {

		// Remove all previous VM trigger
		final Scheduler scheduler = vmSchedulerFactoryBean.getScheduler();
		scheduler.unscheduleJobs(
				new ArrayList<>(scheduler.getTriggerKeys(GroupMatcher.groupEquals(VmResource.SCHEDULE_TRIGGER_GROUP))));
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one
	 * subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, VmResource.SERVICE_KEY);
	}

	@Test
	public void delete() throws SchedulerException {
		final Project project = new Project();
		project.setName("TEST");
		project.setPkey("test");
		em.persist(project);

		final Subscription subscription = new Subscription();
		subscription.setProject(project);
		subscription.setNode(nodeRepository.findOneExpected("service:vm"));
		em.persist(subscription);

		Assert.assertEquals(1, subscriptionRepository.findAllByProject(project.getId()).size());
		em.flush();
		em.clear();

		resource.delete(subscription.getId(), false);
		subscriptionRepository.delete(subscription);
		em.flush();
		em.clear();
		Assert.assertEquals(0, subscriptionRepository.findAllByProject(project.getId()).size());
		Assert.assertEquals(0, vmScheduleRepository.findBySubscription(subscription.getId()).size());
	}

	@Test
	public void saveOrUpdateSchedule() throws Exception {
		// One call would fail
		Mockito.doThrow(new IOException()).when(mockVmTool).execute(subscription, VmOperation.ON);

		// Schedule all operations within the next 3 seconds
		final Calendar calendar = DateUtils.newCalendar();
		resource.saveOrUpdateSchedule(subscription,
				newSchedule("" + ((calendar.get(Calendar.SECOND) + 3) % 60) + " * * * * ? *", VmOperation.OFF));
		resource.saveOrUpdateSchedule(subscription,
				newSchedule("" + ((calendar.get(Calendar.SECOND) + 3) % 60) + " * * * * ? *", VmOperation.ON));
		Assert.assertEquals(2, vmScheduleRepository.findAll().size());
		Thread.sleep(3500);
		Mockito.verify(mockVmTool).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);
	}

	private VmScheduleVo newSchedule(final String cron, final VmOperation operation) {
		final VmScheduleVo result = new VmScheduleVo();
		result.setCron(cron);
		result.setOperation(operation);
		return result;
	}

	@Test
	public void getConfiguration() {
		final VmConfigurationVo configuration = resource.getConfiguration(subscription);
		final List<VmScheduleVo> schedules = configuration.getSchedules();
		Assert.assertEquals(1, schedules.size());
		Assert.assertEquals("0 0 12 ? 2 TUE#4 *", schedules.get(0).getCron());
		Assert.assertEquals(VmOperation.OFF, schedules.get(0).getOperation());
	}

	@Test
	public void execute() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.servicePluginLocator = mockServicePluginLocator;
		resource.execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);
	}

	@Test
	public void unscheduleAll() throws Exception {
		vmScheduleRepository.deleteAll();
		Assert.assertEquals(0, vmScheduleRepository.findAll().size());
		final Calendar calendar = DateUtils.newCalendar();

		// Persist a VM schedule within the next 3 seconds
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);
		resource.persistSchedule(entity, "" + ((calendar.get(Calendar.SECOND) + 3) % 60) + " * * * * ? *",
				VmOperation.ON);
		Assert.assertEquals(1, vmScheduleRepository.findAll().size());

		// Persist another VM schedule for another subscription within the next
		// 3 seconds
		final Subscription otherEntity = new Subscription();
		otherEntity.setProject(entity.getProject());
		otherEntity.setNode(entity.getNode());
		subscriptionRepository.saveAndFlush(otherEntity);
		resource.persistSchedule(otherEntity, "" + "0 0 0 1 1 ? 2050", VmOperation.ON);
		Assert.assertEquals(2, vmScheduleRepository.findAll().size());

		// Load the scheduler from the database
		resource.afterPropertiesSet();
		Assert.assertEquals(2, vmScheduleRepository.findAll().size());

		Thread.sleep(3500);
		Mockito.verify(mockVmTool).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assert.assertEquals(1, vmScheduleRepository.findAll().size());
	}

	@Test
	public void deleteSchedule() throws Exception {

		Assert.assertEquals(1, vmScheduleRepository.findAll().size());
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);

		// Persist another VM schedule for another subscription within the next
		// 3 seconds
		final Subscription otherEntity = new Subscription();
		otherEntity.setProject(entity.getProject());
		otherEntity.setNode(entity.getNode());
		subscriptionRepository.saveAndFlush(otherEntity);
		resource.persistSchedule(otherEntity, "" + "0 0 0 1 1 ? 2050", VmOperation.OFF);
		Assert.assertEquals(2, vmScheduleRepository.findAll().size());

		// Load the scheduler from the database
		resource.afterPropertiesSet();

		resource.deleteSchedule(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);
		Assert.assertEquals(1, vmScheduleRepository.findAll().size());

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
	}

	@Test
	public void deleteScheduleNotScheduled() throws Exception {
		final Calendar calendar = DateUtils.newCalendar();
		vmScheduleRepository.deleteAll();

		// Persist a VM schedule within the next 50 seconds
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);
		resource.persistSchedule(entity, "" + ((calendar.get(Calendar.SECOND) + 50) % 60) + " * * * * ? *",
				VmOperation.ON);
		Assert.assertEquals(1, vmScheduleRepository.findAll().size());

		// Load the scheduler from the database
		resource.afterPropertiesSet();
		resource.deleteSchedule(subscription, VmOperation.OFF);
		resource.unscheduleAll(subscription);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.OFF);
	}

	@Test
	public void getKey() {
		Assert.assertEquals("service:vm", resource.getKey());
	}

	@Test
	public void enumVmStatus() {
		Assert.assertEquals("SUSPENDED", VmStatus.values()[VmStatus.valueOf("SUSPENDED").ordinal()].name());
	}

}
