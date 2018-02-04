package org.ligoj.app.plugin.vm.snapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.transaction.Transactional;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmSnapshotStatusRepository;
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
public class SnapshotResource implements LongTaskRunnerSubscription<VmSnapshotStatus, VmSnapshotStatusRepository> {

	@Autowired
	@Getter
	protected VmSnapshotStatusRepository taskRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	@Getter
	protected SubscriptionRepository subscriptionRepository;

	@Autowired
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
	 * @param subscription
	 *            The related subscription.
	 * @param stop
	 *            When <code>true</code> the relate is stopped before the snapshot.
	 * @return The snapshot task information.
	 * @throws Exception
	 *             Any error while creating the snapshot.
	 */
	@POST
	public VmSnapshotStatus create(@PathParam("subscription") final int subscription,
			@QueryParam("stop") @DefaultValue("false") final boolean stop) {
		// Check the visibility and get the contract implementation
		final Snapshotting snap = getSnapshot(subscriptionResource.checkVisibleSubscription(subscription).getNode());
		log.info("New snapshot requested for subscription {}", subscription);
		final VmSnapshotStatus task = startTask(subscription, t -> {
			t.setWorkload(1);
			t.setDone(0);
			t.setSnapshotInternalId(null);
			t.setStatusText(null);
			t.setPhase(null);
			t.setFinishedRemote(false);
			t.setStop(stop);
		});
		final String user = securityHelper.getLogin();
		// The snapshot execution will done into another thread
		Executors.newSingleThreadExecutor().submit(() -> {
			Thread.sleep(50);
			securityHelper.setUserName(user);
			snap.snapshot(subscription, subscriptionResource.getParametersNoCheck(subscription), task);
			log.info("Snapshot requested for subscription {} finished", subscription);
			return null;
		});
		return task;
	}

	/**
	 * Return all snapshots matching to the given criteria and also associated to
	 * the given subscription.
	 *
	 * @param subscription
	 *            The related subscription identifier.
	 * @param criteria
	 *            The optional search criteria. Case is insensitive. May be the name
	 *            or the identifier for this snapshot.
	 * @return Matching snapshots ordered by descending creation date.
	 * @throws Exception
	 *             Any error while finding the snapshots.
	 */
	@GET
	public List<Snapshot> findAll(@PathParam("subscription") final int subscription,
			@PathParam("q") @DefaultValue("") final String criteria) throws Exception {
		// Check the visibility and get the contract implementation
		return getSnapshot(subscriptionResource.checkVisibleSubscription(subscription).getNode()).findAllSnapshots(subscription, criteria);
	}

	@Override
	public Supplier<VmSnapshotStatus> newTask() {
		return VmSnapshotStatus::new;
	}

	@Override
	@GET
	@Path("task")
	public VmSnapshotStatus getTask(@PathParam("subscription") final Integer subscription) {
		final VmSnapshotStatus task = LongTaskRunnerSubscription.super.getTask(subscription);
		if (task != null) {
			getSnapshot(subscriptionResource.checkVisibleSubscription(subscription).getNode()).completeStatus(task);
		}
		return task;
	}

	@Override
	public boolean isFinished(final VmSnapshotStatus task) {
		// Complete the status for the not completed tasks
		if (task.isFailed()) {
			task.setFinishedRemote(true);
		} else if (!task.isFinishedRemote()) {
			getSnapshot(subscriptionResource.checkVisibleSubscription(task.getLocked().getId()).getNode()).completeStatus(task);
		}
		return task.isFinishedRemote();
	}

}
