/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.execution;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.StreamingOutput;
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
import org.ligoj.app.plugin.vm.VmNetwork;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.*;
import org.ligoj.app.plugin.vm.schedule.VmScheduleResource;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Test class of {@link VmExecutionResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class VmExecutionResourceTest extends AbstractServerTest {
	@Autowired
	private VmResource vmResource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	private VmExecutionRepository vmExecutionRepository;

	@Autowired
	private ServicePluginLocator locator;

	@Autowired
	private SchedulerFactoryBean vmSchedulerFactoryBean;

	protected int subscription;

	private VmExecutionServicePlugin mockVmTool;

	private ServicePluginLocator mockLocator;

	@Autowired
	private SecurityHelper securityHelper;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class<?>[]{Node.class, Project.class, Subscription.class, VmSchedule.class},
				StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter");
	}

	private void mockContext() {
		mockLocator = Mockito.mock(ServicePluginLocator.class);
		mockVmTool = Mockito.mock(VmExecutionServicePlugin.class);
		Mockito.when(mockLocator.getResource(ArgumentMatchers.anyString())).then(invocation -> {
			final var resource = (String) invocation.getArguments()[0];
			if (resource.equals("service:vm:test:test")) {
				return mockVmTool;
			}
			return VmExecutionResourceTest.this.locator.getResource(resource);
		});
		Mockito.when(mockLocator.getResourceExpected(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
				.then(invocation -> {
					final var resource = (String) invocation.getArguments()[0];
					if (resource.equals("service:vm:test:test")) {
						return mockVmTool;
					}
					return VmExecutionResourceTest.this.locator.getResourceExpected(resource,
							(Class<?>) invocation.getArguments()[1]);
				});
		final var applicationContext = Mockito.mock(ApplicationContext.class);
		SpringUtils.setSharedApplicationContext(applicationContext);
		Mockito.when(applicationContext.getBean(ServicePluginLocator.class)).thenReturn(mockLocator);
		Mockito.when(applicationContext.getBean(SecurityHelper.class)).thenReturn(securityHelper);
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
	void execute() throws Exception {
		final var resource = newVmExecutionResource();
		mockContext();
		resource.locator = mockLocator;
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(argument -> {
			argument.setVm("my-vm");
			argument.setStatusText("status");
			return true;
		}));
		final var task = resource.execute(subscription, VmOperation.OFF);
		final var execution = vmExecutionRepository.findOneExpected(task.getExecution().getId());
		Assertions.assertEquals("my-vm", execution.getVm());
		Assertions.assertEquals("status", execution.getStatusText());
		Assertions.assertEquals(VmOperation.OFF, execution.getOperation());
	}

	@Test
	void executeNotFinishedRemote() throws Exception {
		final var resource = newVmExecutionResource();
		mockContext();
		resource.locator = mockLocator;
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(argument -> {
			argument.setVm("my-vm");
			argument.setStatusText("status");
			return true;
		}));
		final var nonBusyVm = new Vm();
		final var busyVm = new Vm();
		busyVm.setBusy(true);
		// First call : no previous task, so "startTask" is accepted.
		// Second call : the task is running but not completed remotely, "endTask" give busy VM
		Mockito.doReturn(busyVm).when(mockVmTool).getVmDetails(ArgumentMatchers.any());

		Assertions.assertNull(resource.getTask(subscription));
		final var task1 = resource.execute(subscription, VmOperation.OFF);

		// Just after an execution, VM status is not fetched
		Assertions.assertNull(task1.getVm());
		Assertions.assertTrue(task1.isFinished());
		Assertions.assertFalse(task1.isFinishedRemote());
		Assertions.assertFalse(resource.getTask(subscription).isFinishedRemote());

		// Next execution --> "startTask" is rejected, not remotely finished
		Assertions.assertThrows(BusinessException.class, () -> resource.execute(subscription, VmOperation.OFF));

		// First call : "startTask" is accepted, VM is no more busy
		// Second call : the task is running but not completed remotely, "endTask" give busy VM
		em.clear();
		Mockito.doReturn(nonBusyVm, busyVm, busyVm, busyVm).when(mockVmTool).getVmDetails(ArgumentMatchers.any());
		final var task2 = resource.execute(subscription, VmOperation.OFF);
		Assertions.assertNotNull(task2.getVm());
		Assertions.assertTrue(task2.isFinished());
		Assertions.assertFalse(task2.isFinishedRemote());
		Assertions.assertFalse(resource.getTask(subscription).isFinishedRemote());

		// The remote state is unavailable
		em.clear();
		Mockito.doThrow(new IllegalStateException()).when(mockVmTool).getVmDetails(ArgumentMatchers.any());
		Assertions.assertFalse(resource.getTask(subscription).isFinishedRemote());

		// Set as remotely finished the task
		em.clear();
		Mockito.doReturn(nonBusyVm).when(mockVmTool).getVmDetails(ArgumentMatchers.any());
		final var task = resource.getTask(subscription);
		Assertions.assertTrue(task.isFinishedRemote());
		Assertions.assertEquals(VmOperation.OFF, task.getOperation());
		Assertions.assertNotNull(task.getVm());

		// The remote VM state is not fetched again
		em.clear();
		Assertions.assertTrue(resource.getTask(subscription).isFinishedRemote());
		Assertions.assertNull(resource.getTask(subscription).getVm());
	}

	/**
	 * Coverage only
	 */
	@Test
	void executeDefault() throws Exception {
		final var execution = new VmExecution();
		final var subscription = new Subscription();
		subscription.setId(1);
		execution.setSubscription(subscription);
		new VmExecutionServicePlugin() {

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
	void executeUnavailablePlugin() {
		final var entity = subscriptionRepository.findOneExpected(subscription);
		final var node = new Node();
		node.setId("_deleted_plugin_");
		node.setName("any");
		nodeRepository.saveAndFlush(node);
		entity.setNode(node);
		subscriptionRepository.saveAndFlush(entity);

		final var task = newVmExecutionResource().execute(entity, VmOperation.OFF);

		// Execution is logged but failed
		final var execution = vmExecutionRepository.findOneExpected(task.getExecution().getId());
		Assertions.assertNull(execution.getVm());
		Assertions.assertNull(execution.getVm());
		Assertions.assertFalse(execution.isSucceed());
		Assertions.assertEquals("fdaugan", execution.getTrigger());
		Assertions.assertEquals(VmOperation.OFF, execution.getOperation());
		Assertions.assertEquals("Plugin issue for _deleted_plugin_:Not found", execution.getError());
	}

	@Test
	void executeError() throws Exception {
		final var resource = newVmExecutionResource();
		mockContext();
		resource.locator = mockLocator;
		Mockito.doThrow(new AssertionError("_some_error_")).when(mockVmTool)
				.execute(ArgumentMatchers.any(VmExecution.class));
		Assertions.assertThrows(AssertionError.class, () -> resource.execute(subscription, VmOperation.OFF));
		Assertions.assertTrue(resource.getTask(subscription).isFinishedRemote());
		Assertions.assertTrue(resource.getTask(subscription).isFailed());
	}

	@Test
	void enumVmStatus() {
		Assertions.assertEquals("SUSPENDED", VmStatus.values()[VmStatus.valueOf("SUSPENDED").ordinal()].name());
	}

	@Test
	void downloadHistoryReport() throws Exception {
		final var resource = newVmExecutionResource();
		mockContext();
		resource.locator = mockLocator;
		final var operation = new AtomicReference<VmOperation>(null);

		// The third call is skipped
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(argument -> {
			argument.setOperation(operation.get());
			return true;
		}));
		Mockito.doReturn(new Vm()).when(mockVmTool).getVmDetails(ArgumentMatchers.any());

		// Report without executions
		var output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadHistoryReport(subscription, "file1").getEntity()).write(output);
		var lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(1, lines.size());
		Assertions.assertEquals(
				"subscription;project;projectKey;projectName;node;dateHMS;timestamp;previousState;operation;vm;trigger;succeed;statusText;errorText",
				lines.getFirst());
		output.close();

		// Manual execution
		operation.set(VmOperation.OFF);
		var task = resource.execute(subscription, VmOperation.OFF);
		vmExecutionRepository.findOneExpected(task.getExecution().getId()).setStatusText("status1");

		// Manual execution by schedule, by pass the security check
		securityHelper.setUserName(SecurityHelper.SYSTEM_USERNAME);
		final var entity = subscriptionRepository.findOneExpected(subscription);
		operation.set(VmOperation.SHUTDOWN);
		task = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(task.getExecution().getId()).setVm("vm1");

		// This call will be skipped
		operation.set(null);
		Assertions.assertNull(resource.execute(entity, VmOperation.REBOOT).getExecution().getOperation());

		// Restore the current user
		initSpringSecurityContext(getAuthenticationName());

		// Report contains these executions (OFF/SHUTDOWN/[REBOOT = skipped])
		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadHistoryReport(subscription, "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(3, lines.size());
		Assertions.assertTrue(lines.get(2)
				.matches("\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;.+;.+;;OFF;;fdaugan;true;status1;"));
		Assertions.assertTrue(lines.get(1)
				.matches("\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;.+;.+;;SHUTDOWN;vm1;_system;true;;"));
		Assertions.assertEquals(2, vmExecutionRepository.findAllBy("subscription.id", subscription).size());
		Assertions.assertEquals(subscription, vmExecutionRepository.findAllBy("subscription.id", subscription).getFirst()
				.getSubscription().getId().intValue());

		// Delete includes executions
		vmResource.delete(subscription, true);
		Assertions.assertEquals(0, vmExecutionRepository.findAllBy("subscription.id", subscription).size());

	}

	private VmExecutionResource newVmExecutionResource() {
		VmExecutionResource resource = new VmExecutionResource() {
			@Override
			public VmExecutionStatus startTask(final Integer lockedId, final Consumer<VmExecutionStatus> initializer) {
				return super.startTask(lockedId, initializer);
			}

			@Override
			public VmExecutionStatus endTask(final Integer lockedId, final boolean failed) {
				return super.endTask(lockedId, failed);
			}

			@Override
			public VmExecutionStatus endTask(final Integer lockedId, final boolean failed,
					final Consumer<VmExecutionStatus> finalizer) {
				return super.endTask(lockedId, failed, finalizer);
			}

		};
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.self = resource;
		return resource;
	}

	@Test
	void downloadNodeSchedulesReport() throws Exception {
		final var resource = newVmExecutionResource();
		mockContext();
		resource.locator = mockLocator;
		final var operation = new AtomicReference<VmOperation>(null);

		// The third call is skipped
		Mockito.doNothing().when(mockVmTool).execute(ArgumentMatchers.argThat(argument -> {
			argument.setOperation(operation.get());
			argument.setPreviousState(VmStatus.POWERED_ON);
			return true;
		}));
		Mockito.doReturn(new Vm()).when(mockVmTool).getVmDetails(ArgumentMatchers.any());

		// Report without executions
		var output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		var lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertEquals(
				"subscription;project;projectKey;projectName;node;cron;operation;lastDateHMS;lastTimestamp;previousState;lastOperation;vm;lastTrigger;lastSucceed;lastStatusText;lastErrorText;nextDateHMS;nextTimestamp",
				lines.getFirst());

		// No last execution available
		Assertions.assertTrue(lines.get(1).matches(
						"\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;0 0 0 1 1 \\? 2050;OFF;;;;;;;;;;2050/01/01 00:00:00;252460\\d{7}"),
				"Was : " + lines.get(1));
		output.close();

		// Manual execution
		operation.set(VmOperation.OFF);
		var task = resource.execute(subscription, VmOperation.OFF);
		vmExecutionRepository.findOneExpected(task.getExecution().getId()).setStatusText("status1");

		// Manual execution by schedule, by pass the security check
		securityHelper.setUserName(SecurityHelper.SYSTEM_USERNAME);
		final var entity = subscriptionRepository.findOneExpected(subscription);
		operation.set(VmOperation.SHUTDOWN);
		task = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(task.getExecution().getId()).setVm("vm1");

		// This call will be skipped
		operation.set(null);
		Assertions.assertNull(resource.execute(entity, VmOperation.REBOOT).getExecution().getOperation());

		// Restore the current user
		initSpringSecurityContext(getAuthenticationName());

		// Report contains only the last executions (OFF/SHUTDOWN/[REBOOT = skipped])
		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertTrue(lines.get(1).matches(
						"\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;0 0 0 1 1 \\? 2050;OFF;.+;.+;POWERED_ON;SHUTDOWN;vm1;_system;true;;;2050/01/01 00:00:00;2524604400000"),
				"Was : " + lines.get(1));

		// Next execution where schedule CRON has been updated
		vmScheduleRepository.findBy("subscription.id", subscription).setCron("INVALID");
		operation.set(VmOperation.SHUTDOWN);
		task = resource.execute(entity, VmOperation.ON);
		vmExecutionRepository.findOneExpected(task.getExecution().getId()).setVm("vm1");

		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test", "file1").getEntity()).write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(2, lines.size());
		Assertions.assertTrue(lines.get(1).matches(
				"\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;INVALID;OFF;.+;.+;POWERED_ON;SHUTDOWN;vm1;fdaugan;true;;;ERROR;ERROR"));

		// Add another schedule to the same subscription, with an execution
		final var schedule = new VmSchedule();
		schedule.setOperation(VmOperation.ON);
		schedule.setCron("0 0 0 1 1 ? 2049");
		schedule.setSubscription(entity);
		vmScheduleRepository.saveAndFlush(schedule);

		final var execution = new VmExecution();
		execution.setDate(Instant.now());
		execution.setSubscription(entity);
		execution.setTrigger("_system");
		execution.setOperation(VmOperation.ON);
		execution.setPreviousState(VmStatus.POWERED_OFF);
		execution.setSucceed(true);
		vmExecutionRepository.saveAndFlush(execution);

		output = new ByteArrayOutputStream();
		((StreamingOutput) resource.downloadNodeSchedulesReport("service:vm:test:test", "file1").getEntity())
				.write(output);
		lines = IOUtils.readLines(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8);
		Assertions.assertEquals(3, lines.size());
		Assertions.assertTrue(lines.get(2).matches(
						"\\d+;\\d+;ligoj-jupiter;Jupiter;service:vm:test:test;0 0 0 1 1 \\? 2049;ON;.+;.+;POWERED_OFF;ON;;_system;true;;;2049/01/01 00:00:00;2493068400000"),
				"Was : " + lines.get(2));
	}

	/**
	 * Very dummy and ugly test, not very proud. But for now the {@link Vm} class is only in the contract of
	 * {@link VmExecutionServicePlugin} and without usage.
	 */
	@Test
	void testVm() {
		final var vm = new Vm();
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
		Assertions.assertEquals("dns", vm.getNetworks().getFirst().getDns());
		Assertions.assertEquals("type", vm.getNetworks().getFirst().getType());
		Assertions.assertEquals("1.2.3.4", vm.getNetworks().getFirst().getIp());
		Assertions.assertEquals(2048, vm.getRam());
		Assertions.assertEquals(1, vm.getCpu());
		Assertions.assertEquals(VmStatus.SUSPENDED, vm.getStatus());
	}
}
