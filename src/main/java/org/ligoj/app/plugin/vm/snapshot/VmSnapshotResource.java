/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;


import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmSnapshotStatusRepository;
import org.ligoj.app.plugin.vm.model.SnapshotOperation;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.subscription.LongTaskRunnerSubscription;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Snapshot task runner.
 */
@Slf4j
@Service
@Path(VmResource.SERVICE_URL + "/{subscription:\\d+}/snapshot")
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public class VmSnapshotResource implements LongTaskRunnerSubscription<VmSnapshotStatus, VmSnapshotStatusRepository> {

	@Autowired
	@Getter
	protected VmSnapshotStatusRepository taskRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	@Getter
	protected SubscriptionRepository subscriptionRepository;

	@Autowired
	@Getter
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected ServicePluginLocator locator;

	private Snapshotting getSnapshot(final Node node) {
		return Optional.ofNullable(locator.getResource(node.getId(), Snapshotting.class))
				.orElseThrow(() -> new BusinessException("snapshot-no-supported", node.getRefined().getId()));
	}

	/**
	 * Create a snapshot.
	 *
	 * @param subscription The related subscription.
	 * @param stop         When <code>true</code> the relate is stopped before the snapshot.
	 * @return The snapshot task information.
	 */
	@POST
	public VmSnapshotStatus create(@PathParam("subscription") final int subscription,
			@QueryParam("stop") @DefaultValue("false") final boolean stop) {
		// Check the visibility and get the contract implementation
		final var snap = getSnapshot(subscriptionResource.checkVisible(subscription).getNode());
		log.info("New snapshot requested for subscription {}", subscription);
		final var task = startTask(subscription, t -> {
			t.setWorkload(1);
			t.setDone(0);
			t.setSnapshotInternalId(null);
			t.setStatusText(null);
			t.setPhase(null);
			t.setFinishedRemote(false);
			t.setOperation(SnapshotOperation.CREATE);
			t.setStop(stop);
		});
		final var user = securityHelper.getLogin();
		// The snapshot execution will be done into another thread
		try (var executor = Executors.newSingleThreadExecutor()) {
			executor.submit(() -> {
				Thread.sleep(50);
				securityHelper.setUserName(user);
				snap.snapshot(task);
				log.info("Snapshot requested for subscription {} finished", subscription);
				return null;
			});
		}
		return task;
	}

	/**
	 * Delete a snapshot by its identifier.
	 *
	 * @param subscription The related subscription.
	 * @param snapshot     The internal snapshot identifier.
	 * @return The snapshot task information.
	 */
	@DELETE
	@Path("{snapshot}")
	public VmSnapshotStatus delete(@PathParam("subscription") final int subscription,
			@PathParam("snapshot") final String snapshot) {
		// Check the visibility and get the contract implementation
		final var snap = getSnapshot(subscriptionResource.checkVisible(subscription).getNode());
		log.info("Snapshot deletion requested for subscription {}, snapshot {}", subscription, snapshot);
		final var task = startTask(subscription, t -> {
			t.setWorkload(1);
			t.setDone(0);
			t.setSnapshotInternalId(snapshot);
			t.setStatusText("deleting");
			t.setPhase(null);
			t.setFinishedRemote(false);
			t.setOperation(SnapshotOperation.DELETE);
			t.setStop(false);
		});
		final var user = securityHelper.getLogin();
		// The snapshot execution will be done into another thread
		try (var executor = Executors.newSingleThreadExecutor()) {
			executor.submit(() -> {
				Thread.sleep(50);
				securityHelper.setUserName(user);
				snap.delete(task);
				log.info("Snapshot deletion requested for subscription {}, snapshot {} finished", subscription, snapshot);
				return null;
			});
		}
		return task;
	}

	/**
	 * Return all snapshots matching to the given criteria and also associated to the given subscription.
	 *
	 * @param subscription The related subscription identifier.
	 * @param criteria     The optional search criteria. Case is insensitive. Might be the name or the identifier for this
	 *                     snapshot.
	 * @return Matching snapshots ordered by descending creation date.
	 * @throws Exception Any error while finding the snapshots.
	 */
	@GET
	public List<Snapshot> findAll(@PathParam("subscription") final int subscription,
			@PathParam("q") @DefaultValue("") final String criteria) throws Exception {
		// Check the visibility and get the contract implementation
		return getSnapshot(subscriptionResource.checkVisible(subscription).getNode()).findAllSnapshots(subscription,
				criteria);
	}

	@Override
	public Supplier<VmSnapshotStatus> newTask() {
		return VmSnapshotStatus::new;
	}

	@Override
	@GET
	@Path("task")
	public VmSnapshotStatus getTask(@PathParam("subscription") final int subscription) {
		final var task = LongTaskRunnerSubscription.super.getTask(subscription);
		if (task != null) {
			getSnapshot(task.getLocked().getNode()).completeStatus(task);
		}
		return task;
	}

	@Override
	public boolean isFinished(final VmSnapshotStatus task) {
		// Complete the status for the not completed tasks
		if (task.isFailed()) {
			task.setFinishedRemote(true);
		} else if (!task.isFinishedRemote()) {
			getSnapshot(subscriptionResource.checkVisible(task.getLocked().getId()).getNode()).completeStatus(task);
		}
		return task.isFinishedRemote();
	}

}
