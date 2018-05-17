/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.dao;

import org.ligoj.app.dao.task.LongTaskSubscriptionRepository;
import org.ligoj.app.plugin.vm.model.VmExecutionStatus;

/**
 * {@link VmExecutionStatus} repository.
 */
public interface VmExecutionStatusRepository extends LongTaskSubscriptionRepository<VmExecutionStatus> {

	// Nothing specific for now
}
