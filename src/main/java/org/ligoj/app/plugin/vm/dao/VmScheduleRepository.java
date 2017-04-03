package org.ligoj.app.plugin.vm.dao;

import java.util.List;

import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.bootstrap.core.dao.RestRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link VmSchedule} repository.
 */
public interface VmScheduleRepository extends RestRepository<VmSchedule, Integer> {

	/**
	 * Return schedules attached to given subscription.
	 * 
	 * @param subscription
	 *            the subscription linking the VM.
	 * @return schedules attached to given subscription.
	 */
	@Query("FROM VmSchedule WHERE subscription.id = ?1 ORDER BY operation")
	List<VmSchedule> findBySubscription(int subscription);

	/**
	 * Delete the schedule of given operation and subscription.
	 * 
	 * @param subscription
	 *            the subscription linking the VM.
	 * @param operation
	 *            the operation to delete.
	 */
	@Query("DELETE VmSchedule WHERE subscription.id = ?1 AND operation = ?2 ORDER BY operation")
	@Modifying
	void deleteBySubscriptionAndOperation(int subscription, VmOperation operation);
}
