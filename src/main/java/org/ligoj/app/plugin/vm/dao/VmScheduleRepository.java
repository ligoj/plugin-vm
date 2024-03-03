/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.dao;

import java.util.List;

import org.ligoj.app.dao.ProjectRepository;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.bootstrap.core.dao.RestRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link VmSchedule} repository.
 */
public interface VmScheduleRepository extends RestRepository<VmSchedule, Integer> {

	/**
	 * Return schedules attached to given subscription.
	 *
	 * @param subscription the subscription linking the VM.
	 * @return The schedules attached to given subscription.
	 */
	@Query("FROM VmSchedule WHERE subscription.id = ?1 ORDER BY operation")
	List<VmSchedule> findBySubscription(int subscription);

	/**
	 * Return the amount of registered schedules for the given subscription.
	 *
	 * @param subscription the subscription linking the VM.
	 * @return The amount of registered schedules for the given subscription.
	 */
	@Query("SELECT COUNT(id) FROM VmSchedule WHERE subscription.id = ?1")
	int countBySubscription(int subscription);

	/**
	 * Return schedules linked to the related node or sub-node. Result is ordered by project.
	 *
	 * @param node The node identifier to filter.
	 * @param user The principal username.
	 * @return The schedules linked to the related node or sub-node.
	 */
	@Query("SELECT vs FROM VmSchedule vs INNER JOIN FETCH vs.subscription AS s INNER JOIN FETCH s.project AS p "
			+ "INNER JOIN FETCH s.node AS n LEFT JOIN p.cacheGroups AS cpg LEFT JOIN cpg.group AS cg"
			+ " WHERE n.id = :node OR n.id LIKE CONCAT(:node, ':%') AND " + ProjectRepository.VISIBLE_PROJECTS
			+ " ORDER BY p.name")
	List<VmSchedule> findAllByNode(String node, String user);

}
