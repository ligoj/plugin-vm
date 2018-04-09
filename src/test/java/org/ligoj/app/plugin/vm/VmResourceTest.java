package org.ligoj.app.plugin.vm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.transaction.Transactional;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
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
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.plugin.vm.schedule.VmScheduleResource;
import org.ligoj.app.plugin.vm.schedule.VmScheduleVo;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.mockito.ArgumentMatcher;
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
					return VmResourceTest.this.servicePluginLocator.getResourceExpected(resource,
							(Class<?>) invocation.getArguments()[1]);
				});
		final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
		SpringUtils.setSharedApplicationContext(applicationContext);
		Mockito.when(applicationContext.getBean(ServicePluginLocator.class)).thenReturn(mockServicePluginLocator);

	}

	@AfterEach
	public void cleanTrigger() throws SchedulerException {

		// Remove all previous VM trigger
		final Scheduler scheduler = vmSchedulerFactoryBean.getScheduler();
		scheduler.unscheduleJobs(new ArrayList<>(
				scheduler.getTriggerKeys(GroupMatcher.groupEquals(VmScheduleResource.SCHEDULE_TRIGGER_GROUP))));
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
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
		Assertions.assertEquals(VmOperation.OFF,
				VmOperation.values()[VmOperation.valueOf(VmOperation.OFF.name()).ordinal()]);
	}

	@Test
	public void getConfigurationSupportSnapshot() throws ParseException {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Mockito.doReturn(Mockito.mock(Snapshotting.class)).when(resource.locator).getResource("service:vm:test:test",
				Snapshotting.class);

		Assertions.assertTrue(resource.getConfiguration(subscription).isSupportSnapshot());
	}

	@Test
	public void execute() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(new ArgumentMatcher<VmExecution>() {

			@Override
			public boolean matches(final VmExecution argument) {
				argument.setVm("my-vm");
				argument.setStatusText("status");
				return true;
			}

		}));
		final int id = resource.execute(subscription, VmOperation.OFF);
		final VmExecution execution = vmExecutionRepository.findOneExpected(id);
		Assertions.assertEquals("my-vm", execution.getVm());
		Assertions.assertEquals("status", execution.getStatusText());
		Assertions.assertEquals(VmOperation.OFF, execution.getOperation());
	}

	/**
	 * Coverage only
	 */
	@Test
	public void executeDefault() throws Exception {
		final VmExecution execution = new VmExecution();
		final Subscription subscription = new Subscription();
		subscription.setId(1);
		execution.setSubscription(subscription);
		new VmServicePlugin() {

			@Override
			public String getKey() {
				return null;
			}

			@Override
			public Vm getVmDetails(Map<String, String> parameters) {
				return null;
			}
		}.execute(execution);
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
		final Integer id = resource.execute(entity, VmOperation.OFF);

		// Execution is logged but failed
		final VmExecution execution = vmExecutionRepository.findOneExpected(id);
		Assertions.assertNull(execution.getVm());
		Assertions.assertNull(execution.getVm());
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
		Mockito.doThrow(new AssertionError("_some_error_")).when(mockVmTool)
				.execute(ArgumentMatchers.any(VmExecution.class));
		Assertions.assertThrows(AssertionError.class, () -> {
			resource.execute(subscription, VmOperation.OFF);
		});
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
	public void downloadHistoryReport() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		final AtomicReference<VmOperation> operation = new AtomicReference<>(null);

		// The third call is skipped
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(new ArgumentMatcher<VmExecution>() {

			@Override
			public boolean matches(final VmExecution argument) {
				argument.setOperation(operation.get());
				return true;
			}
		}));

		// Report without executions
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadHistoryReport(subscription, "file1").getEntity()).write(output);
		List<String> lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(1, lines.size());
		Assertions.assertEquals(
				"subscription;project;projectKey;projectName;node;dateHMS;timestamp;operation;vm;trigger;succeed;statusText;errorText",
				lines.get(0));
		output.close();

		// Manual execution
		operation.set(VmOperation.OFF);
		Integer id = resource.execute(subscription, VmOperation.OFF);
		vmExecutionRepository.findOneExpected(id).setStatusText("status1");

		// Manual execution by schedule, by pass the security check
		securityHelper.setUserName(SecurityHelper.SYSTEM_USERNAME);
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);
		operation.set(VmOperation.SHUTDOWN);
		id = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(id).setVm("vm1");

		// This call will be skipped
		operation.set(null);
		Assertions.assertNull(resource.execute(entity, VmOperation.REBOOT));

		// Restore the current user
		initSpringSecurityContext(getAuthenticationName());

		// Report contains these executions (OFF/SHUTDOWN/[REBOOT = skipped])
		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadHistoryReport(subscription, "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(3, lines.size());
		Assertions.assertTrue(lines.get(2)
				.matches("\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;.+;.+;OFF;;fdaugan;true;status1;"));
		Assertions.assertTrue(lines.get(1)
				.matches("\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;.+;.+;SHUTDOWN;vm1;_system;true;;"));
		Assertions.assertEquals(2, vmExecutionRepository.findAllBy("subscription.id", subscription).size());
		Assertions.assertEquals(subscription, vmExecutionRepository.findAllBy("subscription.id", subscription).get(0)
				.getSubscription().getId().intValue());

		// Delete includes executions
		resource.delete(subscription, true);
		Assertions.assertEquals(0, vmExecutionRepository.findAllBy("subscription.id", subscription).size());

	}

	@Test
	public void downloadNodeSchedulesReport() throws Exception {
		final VmResource resource = new VmResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = mockServicePluginLocator;
		final AtomicReference<VmOperation> operation = new AtomicReference<>(null);

		// The third call is skipped
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(new ArgumentMatcher<VmExecution>() {

			@Override
			public boolean matches(final VmExecution argument) {
				argument.setOperation(operation.get());
				return true;
			}
		}));

		// Report without executions
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		List<String> lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertEquals(
				"subscription;project;projectKey;projectName;node;cron;operation;lastDateHMS;lastTimestamp;lastOperation;vm;lastTrigger;lastSucceed;lastStatusText;lastErrorText;nextDateHMS;nextTimestamp",
				lines.get(0));

		// No last execution available
		Assertions.assertTrue(lines.get(1).matches(
				"\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;0 0 0 1 1 \\? 2050;OFF;;;;;;;;;2050/01/01 00:00:00;2524604400000"),
				"Was : " + lines.get(1));
		output.close();

		// Manual execution
		operation.set(VmOperation.OFF);
		Integer id = resource.execute(subscription, VmOperation.OFF);
		vmExecutionRepository.findOneExpected(id).setStatusText("status1");

		// Manual execution by schedule, by pass the security check
		securityHelper.setUserName(SecurityHelper.SYSTEM_USERNAME);
		final Subscription entity = subscriptionRepository.findOneExpected(subscription);
		operation.set(VmOperation.SHUTDOWN);
		id = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(id).setVm("vm1");

		// This call will be skipped
		operation.set(null);
		Assertions.assertNull(resource.execute(entity, VmOperation.REBOOT));

		// Restore the current user
		initSpringSecurityContext(getAuthenticationName());

		// Report contains only the last executions (OFF/SHUTDOWN/[REBOOT = skipped])
		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertTrue(lines.get(1).matches(
				"\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;0 0 0 1 1 \\? 2050;OFF;.+;.+;SHUTDOWN;vm1;_system;true;;;2050/01/01 00:00:00;2524604400000"),
				"Was : " + lines.get(1));

		// Next execution where schedule CRON has been updated
		vmScheduleRepository.findBy("subscription.id", subscription).setCron("INVALID");
		operation.set(VmOperation.SHUTDOWN);
		id = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(id).setVm("vm1");

		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertTrue(lines.get(1).matches(
				"\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;INVALID;OFF;.+;.+;SHUTDOWN;vm1;fdaugan;true;;;ERROR;ERROR"));

		// Add another schedule to the same subscription, with an execution
		final VmSchedule schedule = new VmSchedule();
		schedule.setOperation(VmOperation.ON);
		schedule.setCron("0 0 0 1 1 ? 2049");
		schedule.setSubscription(entity);
		vmScheduleRepository.saveAndFlush(schedule);

		final VmExecution execution = new VmExecution();
		execution.setDate(new Date());
		execution.setSubscription(entity);
		execution.setTrigger("_system");
		execution.setOperation(VmOperation.ON);
		execution.setSucceed(true);
		vmExecutionRepository.saveAndFlush(execution);

		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test:test", "file1").getEntity())
				.write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(3, lines.size());
		Assertions.assertTrue(lines.get(2).matches(
				"\\d+;\\d+;gfi-gstack;gStack;service:vm:test:test;0 0 0 1 1 \\? 2049;ON;.+;.+;ON;;_system;true;;;2049/01/01 00:00:00;2493068400000"),
				"Was : " + lines.get(2));
	}

	/**
	 * Very dummy and ugly test, not very proud. But for now the {@link Vm} class is only in the contract of
	 * {@link VmServicePlugin} and without usage.
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
