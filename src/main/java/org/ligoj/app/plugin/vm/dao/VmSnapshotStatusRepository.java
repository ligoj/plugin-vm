/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.dao;

import org.ligoj.app.dao.task.LongTaskSubscriptionRepository;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;

/**
 * {@link VmSnapshotStatus} repository.
 */
public interface VmSnapshotStatusRepository extends LongTaskSubscriptionRepository<VmSnapshotStatus> {

	// Nothing specific for now
}
