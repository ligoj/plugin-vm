/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

import java.util.List;

import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;

/**
 * Snapshot contract.
 */
public interface Snapshotting {

	/**
	 * Create a snapshot. Task runner API is used to share the progress.
	 *
	 * @param task A transient instance of the related task, and also linked to a subscription. Note it is a read-only
	 *             view.
	 * @throws Exception Any error while creating the snapshot.
	 */
	void snapshot(VmSnapshotStatus task) throws Exception; // NOSONAR

	/**
	 * Delete a snapshot. Task runner API is used to share the progress.
	 *
	 * @param task A transient instance of the related task, and also linked to a subscription. Note it is a read-only
	 *             view.
	 * @throws Exception Any error while deleting the snapshot.
	 */
	void delete(VmSnapshotStatus task) throws Exception; // NOSONAR

	/**
	 * Return all snapshots matching to the given criteria and also associated to the given subscription.
	 *
	 * @param subscription The related subscription identifier.
	 * @param criteria     The optional search criteria. Case is insensitive. May be the name or the identifier for this
	 *                     snapshot.
	 * @return Matching snapshots ordered by descending creation date.
	 * @throws Exception Any error while finding the snapshots.
	 */
	List<Snapshot> findAllSnapshots(int subscription, String criteria) throws Exception; // NOSONAR

	/**
	 * Complete the task details from the remote state of this task.
	 *
	 * @param task The current not null task to complete.
	 */
	default void completeStatus(final VmSnapshotStatus task) {
		// Nothing to do
	}
}
