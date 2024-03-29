/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.schedule;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.execution.VmExecutionResource;
import org.ligoj.app.plugin.vm.execution.VmExecutionServicePlugin;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.quartz.SchedulerException;
import org.quartz.core.QuartzScheduler;
import org.quartz.core.QuartzSchedulerResources;
import org.quartz.impl.StdScheduler;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.simpl.RAMJobStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;

/**
 * Test class of {@link VmScheduleResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class VmScheduleResourceTest extends AbstractServerTest {

	@Autowired
	private VmScheduleResource resource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private VmScheduleRepository repository;

	@Autowired
	private ServicePluginLocator servicePluginLocator;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	protected int subscription;

	private VmExecutionServicePlugin mockVmTool;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class<?>[]{Node.class, Project.class, Subscription.class, VmSchedule.class},
				StandardCharsets.UTF_8);

		this.subscription = getSubscription("Jupiter");

		final var mockServicePluginLocator = Mockito.mock(ServicePluginLocator.class);
		mockVmTool = Mockito.mock(VmExecutionServicePlugin.class);
		Mockito.when(mockServicePluginLocator.getResource(ArgumentMatchers.anyString())).then(invocation -> {
			final var resource = (String) invocation.getArguments()[0];
			if (resource.equals("service:vm:test:test")) {
				return mockVmTool;
			}
			return VmScheduleResourceTest.this.servicePluginLocator.getResource(resource);
		});
		Mockito.when(mockServicePluginLocator.getResourceExpected(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
				.then(invocation -> {
					final var resource = (String) invocation.getArguments()[0];
					if (resource.equals("service:vm:test:test")) {
						return mockVmTool;
					}
					return VmScheduleResourceTest.this.servicePluginLocator.getResourceExpected(resource,
							(Class<?>) invocation.getArguments()[1]);
				});
		final var applicationContext = Mockito.mock(ApplicationContext.class);
		SpringUtils.setSharedApplicationContext(applicationContext);
		Mockito.when(applicationContext.getBean(ServicePluginLocator.class)).thenReturn(mockServicePluginLocator);

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
	void createAndUpdateSchedule() throws Exception {
		final var mockContext = Mockito.mock(ApplicationContext.class);
		final var repository = Mockito.mock(VmScheduleRepository.class);
		final var mockResource = Mockito.mock(VmExecutionResource.class);
		final var entity = this.subscriptionRepository.findOneExpected(subscription);
		Mockito.when(mockContext.getBean(VmScheduleRepository.class)).thenReturn(repository);
		Mockito.when(mockContext.getBean(SecurityHelper.class)).thenReturn(Mockito.mock(SecurityHelper.class));
		Mockito.when(mockContext.getBean(VmExecutionResource.class)).thenReturn(mockResource);

		final var scheduler = (StdScheduler) vmSchedulerFactoryBean.getScheduler();
		final var qScheduler = (QuartzScheduler) FieldUtils.getField(StdScheduler.class, "sched", true).get(scheduler);
		final var resources = (QuartzSchedulerResources) FieldUtils.getField(QuartzScheduler.class, "resources", true)
				.get(qScheduler);
		final var jobDetail = scheduler
				.getJobDetail(scheduler.getJobKeys(GroupMatcher.anyJobGroup()).iterator().next());

		// "ON" call would fail
		Mockito.doThrow(new RuntimeException()).when(mockResource).execute(entity, VmOperation.ON);

		try {
			// Mock the factory
			jobDetail.getJobDataMap().put("context", mockContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);

			Assertions.assertEquals(1, this.repository.findAll().size());

			// Schedule all operations within the next 2 seconds
			final var cron = ((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ?";
			final var id = mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.OFF)));
			mockSchedule(repository, resource.create(subscription, newSchedule(cron + " *", VmOperation.ON)));
			Assertions.assertEquals(3, this.repository.findAll().size());

			// Yield for the schedules
			Thread.sleep(2500);

			// Check the executions
			Mockito.verify(mockResource).execute(entity, VmOperation.OFF);
			Mockito.verify(mockResource).execute(entity, VmOperation.ON); // Failed
			Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.REBOOT);
			Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.RESET);
			Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.SHUTDOWN);
			Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.SUSPEND);

			// Update the CRON and the operation
			final var vo = newSchedule(((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ?",
					VmOperation.SHUTDOWN);
			vo.setId(id);
			resource.update(subscription, vo);
			Assertions.assertEquals(3, this.repository.findAll().size());

			// Yield for the schedules
			Thread.sleep(2500);
			Mockito.verify(mockResource).execute(entity, VmOperation.SHUTDOWN);
		} finally {
			// Restore the factory's context
			jobDetail.getJobDataMap().put("context", applicationContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);
		}
	}

	private int mockSchedule(final VmScheduleRepository repository, final int id) {
		Mockito.when(repository.findOneExpected(id)).thenReturn(this.repository.findOneExpected(id));
		return id;
	}

	@Test
	void createInvalidCron() {
		final var schedule = newSchedule("ERROR_CRON", VmOperation.OFF);
		MatcherUtil.assertThrows(
				Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription, schedule)),
				"cron", "vm-cron");
	}

	@Test
	void createInvalidCronEverySecond() {
		final var schedule = newSchedule("* * * ? * *", VmOperation.OFF);
		MatcherUtil.assertThrows(
				Assertions.assertThrows(ValidationJsonException.class, () -> resource.create(subscription, schedule)),
				"cron", "vm-cron-second");
	}

	private VmScheduleVo newSchedule(final String cron, final VmOperation operation) {
		final var result = new VmScheduleVo();
		result.setCron(cron);
		result.setOperation(operation);
		return result;
	}

	@Test
	void deleteScheduleAll() throws Exception {
		Assertions.assertEquals(1, repository.findAll().size());
		repository.deleteAll();
		Assertions.assertEquals(0, repository.findAll().size());
		final var entity = this.subscriptionRepository.findOneExpected(subscription);

		final var mockContext = Mockito.mock(ApplicationContext.class);
		final var repository = Mockito.mock(VmScheduleRepository.class);
		final var mockResource = Mockito.mock(VmExecutionResource.class);
		Mockito.when(mockContext.getBean(VmScheduleRepository.class)).thenReturn(repository);
		Mockito.when(mockContext.getBean(SecurityHelper.class)).thenReturn(Mockito.mock(SecurityHelper.class));
		Mockito.when(mockContext.getBean(VmExecutionResource.class)).thenReturn(mockResource);

		final var scheduler = (StdScheduler) vmSchedulerFactoryBean.getScheduler();
		final var qScheduler = (QuartzScheduler) FieldUtils.getField(StdScheduler.class, "sched", true).get(scheduler);
		final var resources = (QuartzSchedulerResources) FieldUtils.getField(QuartzScheduler.class, "resources", true)
				.get(qScheduler);
		final var jobDetail = scheduler
				.getJobDetail(scheduler.getJobKeys(GroupMatcher.anyJobGroup()).iterator().next());

		// One call would fail
		Mockito.doThrow(new RuntimeException()).when(mockResource).execute(entity, VmOperation.ON);
		final var otherEntity = new Subscription();

		try {
			// Mock the factory
			jobDetail.getJobDataMap().put("context", mockContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);

			// Schedule all operations within the next 2 seconds
			final var cron = ((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ? *";
			mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.ON)));
			mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.ON)));
			mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.ON)));
			mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.ON)));
			mockSchedule(repository, resource.create(subscription, newSchedule(cron, VmOperation.ON)));
			Assertions.assertEquals(5, this.repository.findAll().size());

			// Persist another VM schedule for another subscription within the
			// next 2 seconds
			otherEntity.setProject(entity.getProject());
			otherEntity.setNode(entity.getNode());
			this.subscriptionRepository.saveAndFlush(otherEntity);
			final var schedule2 = newSchedule("0 0 0 1 1 ? 2050", VmOperation.ON);
			resource.create(otherEntity.getId(), schedule2);
			Assertions.assertEquals(6, this.repository.findAll().size());

			// Yield for the schedules
			Thread.sleep(2500);
		} finally {
			// Restore the factory's context
			jobDetail.getJobDataMap().put("context", applicationContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);
		}
		Mockito.inOrder(mockResource).verify(mockResource, Mockito.calls(5)).execute(entity, VmOperation.ON);
		Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.OFF);
		Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.REBOOT);
		Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.RESET);
		Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.SHUTDOWN);
		Mockito.verify(mockResource, Mockito.never()).execute(entity, VmOperation.SUSPEND);

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(1, this.repository.findAll().size());
		resource.unscheduleAll(otherEntity.getId());
		Assertions.assertEquals(0, this.repository.findAll().size());
	}

	@Test
	void delete() throws Exception {
		Assertions.assertEquals(1, repository.findAll().size());
		final var entity = subscriptionRepository.findOneExpected(subscription);

		// Persist another VM schedule for another subscription
		final var otherEntity = new Subscription();
		otherEntity.setProject(entity.getProject());
		otherEntity.setNode(entity.getNode());
		subscriptionRepository.saveAndFlush(otherEntity);
		final var schedule2 = newSchedule("0 0 0 1 1 ? 2050", VmOperation.OFF);
		final var schedule = resource.create(otherEntity.getId(), schedule2);
		Assertions.assertEquals(2, repository.findAll().size());

		resource.delete(otherEntity.getId(), schedule);
		Assertions.assertEquals(1, repository.findAll().size());

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, repository.findAll().size());
	}

	@Test
	void deleteInvalidSubscription() throws Exception {
		Assertions.assertEquals(1, repository.findAll().size());
		final var entity = subscriptionRepository.findOneExpected(subscription);

		// Persist another VM schedule for another subscription
		final var otherEntity = new Subscription();
		otherEntity.setProject(entity.getProject());
		otherEntity.setNode(entity.getNode());
		subscriptionRepository.saveAndFlush(otherEntity);
		final var schedule2 = newSchedule("0 0 0 1 1 ? 2050", VmOperation.OFF);
		final var schedule = resource.create(otherEntity.getId(), schedule2);
		Assertions.assertEquals(2, repository.findAll().size());

		Assertions.assertThrows(EntityNotFoundException.class, () -> resource.delete(subscription, schedule));
	}

	@Test
	void afterPropertiesSet() throws Exception {
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, repository.findAll().size());

		// Persist again the schedule without involving Quartz
		persistEntities("csv", new Class<?>[]{VmSchedule.class}, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, repository.findAll().size());
		resource.afterPropertiesSet();
		Assertions.assertEquals(1, repository.findAll().size());

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, repository.findAll().size());
	}
}
