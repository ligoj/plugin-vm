package org.ligoj.app.plugin.vm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.transaction.Transactional;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.MatcherUtil;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
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

/**
 * Test class of {@link VmResource}
 */
@ExtendWith(SpringExtension.class)
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
	private VmExecutionRepository vmExecutionRepository;

	@Autowired
	private ServicePluginLocator servicePluginLocator;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	protected int subscription;

	private VmServicePlugin mockVmTool;

	private ServicePluginLocator mockServicePluginLocator;

	@Autowired
	private SecurityHelper securityHelper;

	@BeforeEach
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
					return VmResourceTest.this.servicePluginLocator.getResourceExpected(resource, (Class<?>) invocation.getArguments()[1]);
				});
		final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
		SpringUtils.setSharedApplicationContext(applicationContext);
		Mockito.when(applicationContext.getBean(ServicePluginLocator.class)).thenReturn(mockServicePluginLocator);

	}

	@AfterEach
	public void cleanTrigger() throws SchedulerException {

		// Remove all previous VM trigger
		final Scheduler scheduler = vmSchedulerFactoryBean.getScheduler();
		scheduler.unscheduleJobs(new ArrayList<>(scheduler.getTriggerKeys(GroupMatcher.groupEquals(VmResource.SCHEDULE_TRIGGER_GROUP))));
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
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
	public void createAndUpdateSchedule() throws Exception {
		final ApplicationContext mockContext = Mockito.mock(ApplicationContext.class);
		final VmScheduleRepository vmScheduleRepository = Mockito.mock(VmScheduleRepository.class);
		final VmResource mockResource = Mockito.mock(VmResource.class);
		final Subscription entity = this.subscriptionRepository.findOneExpected(subscription);
		Mockito.when(mockContext.getBean(VmScheduleRepository.class)).thenReturn(vmScheduleRepository);
		Mockito.when(mockContext.getBean(SecurityHelper.class)).thenReturn(Mockito.mock(SecurityHelper.class));
		Mockito.when(mockContext.getBean(VmResource.class)).thenReturn(mockResource);

		final StdScheduler scheduler = (StdScheduler) vmSchedulerFactoryBean.getScheduler();
		final QuartzScheduler qscheduler = (QuartzScheduler) FieldUtils.getField(StdScheduler.class, "sched", true).get(scheduler);
		final QuartzSchedulerResources resources = (QuartzSchedulerResources) FieldUtils.getField(QuartzScheduler.class, "resources", true)
				.get(qscheduler);
		final JobDetail jobDetail = scheduler.getJobDetail(scheduler.getJobKeys(GroupMatcher.anyJobGroup()).iterator().next());

		// "ON" call would fail
		Mockito.doThrow(new RuntimeException()).when(mockResource).execute(entity, VmOperation.ON);

		try {
			// Mock the factory
			jobDetail.getJobDataMap().put("context", mockContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);

			Assertions.assertEquals(1, this.vmScheduleRepository.findAll().size());

			// Schedule all operations within the next 2 seconds
			final String cron = "" + ((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ?";
			final int id = mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.OFF)));
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron + " *", VmOperation.ON)));
			Assertions.assertEquals(3, this.vmScheduleRepository.findAll().size());

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
			final VmScheduleVo vo = newSchedule("" + ((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ?",
					VmOperation.SHUTDOWN);
			vo.setId(id);
			vo.setSubscription(subscription);
			resource.updateSchedule(vo);
			Assertions.assertEquals(3, this.vmScheduleRepository.findAll().size());

			// Yield for the schedules
			Thread.sleep(2500);
			Mockito.verify(mockResource).execute(entity, VmOperation.SHUTDOWN);
		} finally {
			// Restore the factory's context
			jobDetail.getJobDataMap().put("context", applicationContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);
		}
	}

	private int mockSchedule(final VmScheduleRepository vmScheduleRepository, final int id) {
		Mockito.when(vmScheduleRepository.findOneExpected(id)).thenReturn(this.vmScheduleRepository.findOneExpected(id));
		return id;
	}

	@Test
	public void createScheduleInvalidCron() {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.createSchedule(newSchedule("ERROR_CRON", VmOperation.OFF));
		}), "cron", "vm-cron");
	}

	@Test
	public void createScheduleInvalidCronEverySecond() {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.createSchedule(newSchedule("* * * ? * *", VmOperation.OFF));
		}), "cron", "vm-cron-second");
	}

	private VmScheduleVo newSchedule(final String cron, final VmOperation operation) {
		final VmScheduleVo result = new VmScheduleVo();
		result.setCron(cron);
		result.setOperation(operation);
		result.setSubscription(subscription);
		return result;
	}

	@Test
	public void getConfiguration() throws ParseException {
		final VmConfigurationVo configuration = resource.getConfiguration(subscription);
		final List<VmScheduleVo> schedules = configuration.getSchedules();
		Assertions.assertEquals(1, schedules.size());
		Assertions.assertEquals("0 0 0 1 1 ? 2050", schedules.get(0).getCron());
		Assertions.assertEquals(getDate(2050, 1, 1, 0, 0, 0), schedules.get(0).getNext());
		Assertions.assertNotNull(schedules.get(0).getId());
		Assertions.assertEquals(VmOperation.OFF, schedules.get(0).getOperation());
		Assertions.assertFalse(configuration.isSupportSnapshot());

		// Coverage only
		Assertions.assertEquals(VmOperation.OFF, VmOperation.values()[VmOperation.valueOf(VmOperation.OFF.name()).ordinal()]);
	}

	@Test
	public void getConfigurationSupportSnapshot() throws ParseException {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Mockito.doReturn(Mockito.mock(Snapshotting.class)).when(resource.locator).getResource("service:vm:test:test", Snapshotting.class);

		Assertions.assertTrue(resource.getConfiguration(subscription).isSupportSnapshot());
	}

	@Test
	public void execute() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		resource.execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);
	}

	@Test
	public void executeUnavailablePlugin() throws Exception {
		final VmResource resource = new VmResource();
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);
		final Node node = new Node();
		node.setId("_deleted_plugin_");
		node.setName("any");
		nodeRepository.saveAndFlush(node);
		entity.setNode(node);
		subscriptionRepository.saveAndFlush(entity);

		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		resource.execute(entity, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);

		Assertions.assertEquals(1, vmExecutionRepository.findAllBy("subscription.id", subscription).size());
		final VmExecution execution = vmExecutionRepository.findBy("subscription.id", subscription);
		Assertions.assertFalse(execution.isSucceed());
		Assertions.assertEquals("fdaugan", execution.getTrigger());
		Assertions.assertEquals(VmOperation.OFF, execution.getOperation());
		Assertions.assertEquals("Plugin issue for _deleted_plugin_:Not found", execution.getError());
	}

	@Test
	public void executeError() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		Mockito.doThrow(new AssertionError("_some_error_")).when(mockVmTool).execute(subscription, VmOperation.OFF);
		Assertions.assertThrows(AssertionError.class, () -> {
			resource.execute(subscription, VmOperation.OFF);
		});
	}

	@Test
	public void unscheduleAll() throws Exception {
		Assertions.assertEquals(1, vmScheduleRepository.findAll().size());
		vmScheduleRepository.deleteAll();
		Assertions.assertEquals(0, vmScheduleRepository.findAll().size());
		final Subscription entity = this.subscriptionRepository.findOneExpected(subscription);

		final ApplicationContext mockContext = Mockito.mock(ApplicationContext.class);
		final VmScheduleRepository vmScheduleRepository = Mockito.mock(VmScheduleRepository.class);
		final VmResource mockResource = Mockito.mock(VmResource.class);
		Mockito.when(mockContext.getBean(VmScheduleRepository.class)).thenReturn(vmScheduleRepository);
		Mockito.when(mockContext.getBean(SecurityHelper.class)).thenReturn(Mockito.mock(SecurityHelper.class));
		Mockito.when(mockContext.getBean(VmResource.class)).thenReturn(mockResource);

		final StdScheduler scheduler = (StdScheduler) vmSchedulerFactoryBean.getScheduler();
		final QuartzScheduler qscheduler = (QuartzScheduler) FieldUtils.getField(StdScheduler.class, "sched", true).get(scheduler);
		final QuartzSchedulerResources resources = (QuartzSchedulerResources) FieldUtils.getField(QuartzScheduler.class, "resources", true)
				.get(qscheduler);
		final JobDetail jobDetail = scheduler.getJobDetail(scheduler.getJobKeys(GroupMatcher.anyJobGroup()).iterator().next());

		// One call would fail
		Mockito.doThrow(new RuntimeException()).when(mockResource).execute(entity, VmOperation.ON);
		final Subscription otherEntity = new Subscription();

		try {
			// Mock the factory
			jobDetail.getJobDataMap().put("context", mockContext);
			((RAMJobStore) resources.getJobStore()).storeJob(jobDetail, true);

			// Schedule all operations within the next 2 seconds
			final String cron = "" + ((DateUtils.newCalendar().get(Calendar.SECOND) + 2) % 60) + " * * * * ? *";
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.ON)));
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.ON)));
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.ON)));
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.ON)));
			mockSchedule(vmScheduleRepository, resource.createSchedule(newSchedule(cron, VmOperation.ON)));
			Assertions.assertEquals(5, this.vmScheduleRepository.findAll().size());

			// Persist another VM schedule for another subscription within the
			// next 2 seconds
			otherEntity.setProject(entity.getProject());
			otherEntity.setNode(entity.getNode());
			this.subscriptionRepository.saveAndFlush(otherEntity);
			final VmScheduleVo schedule2 = newSchedule("0 0 0 1 1 ? 2050", VmOperation.ON);
			schedule2.setSubscription(otherEntity.getId());
			resource.createSchedule(schedule2);
			Assertions.assertEquals(6, this.vmScheduleRepository.findAll().size());

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
		Assertions.assertEquals(1, this.vmScheduleRepository.findAll().size());
		resource.unscheduleAll(otherEntity.getId());
		Assertions.assertEquals(0, this.vmScheduleRepository.findAll().size());
	}

	@Test
	public void deleteSchedule() throws Exception {
		Assertions.assertEquals(1, vmScheduleRepository.findAll().size());
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);

		// Persist another VM schedule for another subscription
		final Subscription otherEntity = new Subscription();
		otherEntity.setProject(entity.getProject());
		otherEntity.setNode(entity.getNode());
		subscriptionRepository.saveAndFlush(otherEntity);
		final VmScheduleVo schedule2 = newSchedule("0 0 0 1 1 ? 2050", VmOperation.OFF);
		schedule2.setSubscription(otherEntity.getId());
		final int schedule = resource.createSchedule(schedule2);
		Assertions.assertEquals(2, vmScheduleRepository.findAll().size());

		resource.deleteSchedule(schedule);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.ON);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.OFF);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.REBOOT);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.RESET);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SHUTDOWN);
		Mockito.verify(mockVmTool, Mockito.never()).execute(subscription, VmOperation.SUSPEND);
		Assertions.assertEquals(1, vmScheduleRepository.findAll().size());

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, vmScheduleRepository.findAll().size());
	}

	@Test
	public void afterPropertiesSet() throws Exception {
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, vmScheduleRepository.findAll().size());

		// Persist again the schedule without involving Quartz
		persistEntities("csv", new Class[] { VmSchedule.class }, StandardCharsets.UTF_8.name());
		Assertions.assertEquals(1, vmScheduleRepository.findAll().size());
		resource.afterPropertiesSet();
		Assertions.assertEquals(1, vmScheduleRepository.findAll().size());

		// Remove all triggers of the subscription
		resource.unscheduleAll(subscription);
		Assertions.assertEquals(0, vmScheduleRepository.findAll().size());
	}

	@Test
	public void getKey() {
		Assertions.assertEquals("service:vm", resource.getKey());
	}

	@Test
	public void enumVmStatus() {
		Assertions.assertEquals("SUSPENDED", VmStatus.values()[VmStatus.valueOf("SUSPENDED").ordinal()].name());
	}

	@Test
	public void downloadReport() throws IOException, SchedulerException {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;

		// Report without executions
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadReport(subscription, "file1").getEntity()).write(output);
		List<String> lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(1, lines.size());
		Assertions.assertEquals("dateHMS;timestamp;operation;subscription;project;projectKey;projectName;node;trigger;succeed",
				lines.get(0));
		output.close();

		// Manual execution
		resource.execute(subscription, VmOperation.OFF);

		// Manual execution by schedule, by pass the security check
		securityHelper.setUserName(SecurityHelper.SYSTEM_USERNAME);
		resource.execute(subscriptionRepository.findOneExpected(subscription), VmOperation.ON);

		// Restore the current user
		initSpringSecurityContext(getAuthenticationName());

		// Report contains these executions (OFF/ON)
		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadReport(subscription, "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(3, lines.size());
		Assertions.assertTrue(lines.get(1).endsWith(";gfi-gstack;gStack;service:vm:test:test;fdaugan;true"));
		Assertions.assertTrue(lines.get(1).contains(";OFF;"));
		Assertions.assertTrue(lines.get(2).endsWith(";gfi-gstack;gStack;service:vm:test:test;_system;true"));
		Assertions.assertTrue(lines.get(2).contains(";ON;"));
		Assertions.assertEquals(2, vmExecutionRepository.findAllBy("subscription.id", subscription).size());
		Assertions.assertEquals(subscription,
				vmExecutionRepository.findAllBy("subscription.id", subscription).get(0).getSubscription().getId().intValue());

		// Delete includes executions
		resource.delete(subscription, true);
		Assertions.assertEquals(0, vmExecutionRepository.findAllBy("subscription.id", subscription).size());

	}

	/**
	 * Very dummy and ugly test, not very proud. But for now the {@link Vm} class is
	 * only in the contract of {@link VmServicePlugin} and without usage.
	 */
	@Test
	public void testVm() {
		final Vm vm = new Vm();
		vm.setBusy(true);
		vm.setCpu(1);
		vm.setDeployed(true);
		vm.setOs("os");
		vm.setRam(2048);
		vm.setStatus(VmStatus.SUSPENDED);
		vm.setNetworks(Collections.singletonList(new VmNetwork("type", "1.2.3.4", "dns")));
		vm.setName("name");
		Assertions.assertTrue(vm.isBusy());
		Assertions.assertTrue(vm.isDeployed());
		Assertions.assertEquals("name", vm.getName());
		Assertions.assertEquals("os", vm.getOs());
		Assertions.assertEquals(1, vm.getNetworks().size());
		Assertions.assertEquals("dns", vm.getNetworks().get(0).getDns());
		Assertions.assertEquals("type", vm.getNetworks().get(0).getType());
		Assertions.assertEquals("1.2.3.4", vm.getNetworks().get(0).getIp());
		Assertions.assertEquals(2048, vm.getRam());
		Assertions.assertEquals(1, vm.getCpu());
		Assertions.assertEquals(VmStatus.SUSPENDED, vm.getStatus());
	}
}
