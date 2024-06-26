/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.iam.SimpleUser;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmSnapshotStatusRepository;
import org.ligoj.app.plugin.vm.model.SnapshotOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link VmSnapshotResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class VmSnapshotResourceTest extends AbstractServerTest {

	private VmSnapshotResource resource;

	protected int subscription;

	private Snapshotting service;

	private VmSnapshotStatus status;

	@Autowired
	private VmSnapshotStatusRepository repository;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class<?>[]{Node.class, Project.class, Subscription.class, VmSchedule.class},
				StandardCharsets.UTF_8);

		subscription = getSubscription("Jupiter");
		service = Mockito.mock(Snapshotting.class);
		status = new VmSnapshotStatus();
		resource = new VmSnapshotResource() {
			@Override
			public VmSnapshotStatus getTask(final int subscription) {
				return getTaskRepository().findBy("locked.id", subscription);
			}
		};
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Mockito.doReturn(service).when(resource.locator).getResource("service:vm:test:test", Snapshotting.class);
	}

	@Test
	void completeStatus() {
		new Snapshotting() {

			@Override
			public void snapshot(final VmSnapshotStatus transientTask) {
				// No implementation
			}

			@Override
			public List<Snapshot> findAllSnapshots(int subscription, String criteria) {
				// No implementation
				return null;
			}

			@Override
			public void delete(final VmSnapshotStatus transientTask) {
				// No implementation
			}
		}.completeStatus(null);
	}

	@Test
	void createNotSupported() {
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Assertions.assertEquals("snapshot-no-supported", Assertions.assertThrows(BusinessException.class, () -> resource.create(subscription, true)).getMessage());
	}

	@Test
	void create() throws Exception {
		status = resource.create(subscription, true);
		Thread.sleep(200);
		Mockito.verify(service).snapshot(ArgumentMatchers.any(VmSnapshotStatus.class));
		Assertions.assertFalse(status.isFinishedRemote());
		Assertions.assertTrue(status.isStop());
		Assertions.assertEquals(getAuthenticationName(), status.getAuthor());
		Assertions.assertNull(status.getEnd());
		Assertions.assertNull(status.getSnapshotInternalId());
		Assertions.assertNull(status.getStatusText());
		Assertions.assertNull(status.getPhase());
		Assertions.assertEquals(subscription, status.getLocked().getId().intValue());
		Assertions.assertNotNull(status.getStart());
		Assertions.assertEquals(0, status.getDone());
		Assertions.assertEquals(1, status.getWorkload());
		Assertions.assertEquals(SnapshotOperation.CREATE, status.getOperation());
		Assertions.assertFalse(resource.getTask(subscription).isFinished());
	}

	@Test
	void delete() throws Exception {
		status = resource.delete(subscription, "snapshot-id");
		Thread.sleep(200);
		Mockito.verify(service).delete(ArgumentMatchers.any(VmSnapshotStatus.class));
		Assertions.assertFalse(status.isFinishedRemote());
		Assertions.assertFalse(status.isStop());
		Assertions.assertEquals(getAuthenticationName(), status.getAuthor());
		Assertions.assertNull(status.getEnd());
		Assertions.assertEquals("snapshot-id", status.getSnapshotInternalId());
		Assertions.assertEquals("deleting", status.getStatusText());
		Assertions.assertNull(status.getPhase());
		Assertions.assertEquals(subscription, status.getLocked().getId().intValue());
		Assertions.assertNotNull(status.getStart());
		Assertions.assertEquals(0, status.getDone());
		Assertions.assertEquals(1, status.getWorkload());
		Assertions.assertEquals(SnapshotOperation.DELETE, status.getOperation());
		Assertions.assertFalse(resource.getTask(subscription).isFinished());
	}

	@Test
	void findAll() throws Exception {
		var snapshot = new Snapshot();
		snapshot.setAuthor(new SimpleUser());
		snapshot.setAvailable(true);
		snapshot.setDate(new Date());
		snapshot.setDescription("description");
		snapshot.setPending(true);
		snapshot.setStatusText("status");
		snapshot.setStopRequested(true);
		snapshot.setOperation(SnapshotOperation.CREATE);

		final var volumeSnapshot = new VolumeSnapshot();
		volumeSnapshot.setId("snap");
		volumeSnapshot.setSize(10);
		volumeSnapshot.setName("/dev");
		snapshot.setVolumes(Collections.singletonList(volumeSnapshot));
		Mockito.doReturn(Collections.singletonList(snapshot)).when(service).findAllSnapshots(subscription, "criteria");

		final var list = resource.findAll(subscription, "criteria");
		Assertions.assertEquals(1, list.size());
		snapshot = list.getFirst();

		// Coverage only for the API
		Assertions.assertNotNull(snapshot.getAuthor());
		Assertions.assertTrue(snapshot.isAvailable());
		Assertions.assertTrue(snapshot.getStopRequested());
		Assertions.assertNotNull(snapshot.getDate());
		Assertions.assertEquals("description", snapshot.getDescription());
		Assertions.assertTrue(snapshot.isPending());
		Assertions.assertEquals("status", snapshot.getStatusText());
		Assertions.assertEquals(SnapshotOperation.CREATE, snapshot.getOperation());
		Assertions.assertEquals(1, snapshot.getVolumes().size());
		Assertions.assertEquals("snap", volumeSnapshot.getId());
		Assertions.assertEquals(10, volumeSnapshot.getSize());
		Assertions.assertEquals("/dev", volumeSnapshot.getName());
		Assertions.assertEquals(SnapshotStatus.AVAILABLE, SnapshotStatus.valueOf(SnapshotStatus.values()[0].name()));
		Assertions.assertEquals(SnapshotOperation.CREATE,
				SnapshotOperation.valueOf(SnapshotOperation.values()[0].name()));
	}

	@Test
	void getTask() {
		// Add a running task
		final var oldTask = new VmSnapshotStatus();
		oldTask.setAuthor("junit");
		oldTask.setStart(new Date());
		oldTask.setOperation(SnapshotOperation.CREATE);
		oldTask.setLocked(subscriptionRepository.findOneExpected(subscription));
		repository.saveAndFlush(oldTask);
		mockProxy();

		final var task = resource.getTask(subscription);
		Mockito.verify(service).completeStatus(ArgumentMatchers.any(VmSnapshotStatus.class));
		Assertions.assertEquals("junit", task.getAuthor());
		Assertions.assertEquals(SnapshotOperation.CREATE, task.getOperation());
	}

	@Test
	void getTaskNull() {
		mockProxy();
		final var task = resource.getTask(subscription);
		Mockito.verify(service, Mockito.never()).completeStatus(ArgumentMatchers.any(VmSnapshotStatus.class));
		Assertions.assertNull(task);
	}

	@Test
	void isFinished() {
		final var task = new VmSnapshotStatus();
		task.setFinishedRemote(true);
		Assertions.assertTrue(resource.isFinished(task));
	}

	@Test
	void isFinishedFailed() {
		final var task = new VmSnapshotStatus();
		task.setFinishedRemote(true);
		task.setFailed(true);
		Assertions.assertTrue(resource.isFinished(task));
	}

	@Test
	void isFinishedNotFinishedRemote() {
		final var task = new VmSnapshotStatus();
		task.setLocked(subscriptionRepository.findOneExpected(subscription));
		Assertions.assertFalse(resource.isFinished(task));
		Mockito.verify(service).completeStatus(task);
	}

	/**
	 * Pure proxy without internal transaction.
	 */
	private void mockProxy() {
		resource = new VmSnapshotResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.locator = Mockito.mock(ServicePluginLocator.class);
		Mockito.doReturn(service).when(resource.locator).getResource("service:vm:test:test", Snapshotting.class);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, VmResource.SERVICE_KEY);
	}
}
