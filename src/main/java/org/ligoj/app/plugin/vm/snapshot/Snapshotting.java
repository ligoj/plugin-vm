package org.ligoj.app.plugin.vm.snapshot;

import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;

/**
 * Snapshot contract.
 */
public interface Snapshotting {

	/**
	 * Create a snapshot. Task runner API is used to share the progress.
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @param parameters
	 *            the subscription parameters.
	 * @param stop
	 *            When <code>true</code> the relate is stopped before the snapshot.
	 * @throws Exception
	 *             Any error while creating the snapshot.
	 */
	void snapshot(int subscription, Map<String, String> parameters, boolean stop) throws Exception;

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
	List<Snapshot> findAllSnapshots(int subscription, String criteria) throws Exception;

	/**
	 * Complete the task details from the remote state of this task.
	 * 
	 * @param task
	 *            The current not null task to complete.
	 */
	default void completeStatus(final VmSnapshotStatus task) {
		// Nothing to do
	}
}
