package org.ligoj.app.plugin.vm.dao;

import java.util.List;

import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.bootstrap.core.dao.RestRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link VmExecution} repository.
 */
public interface VmExecutionRepository extends RestRepository<VmExecution, Integer> {

	/**
	 * Return the each last executions related to the given node or sub-node. Security is not enabled.
	 * 
	 * @param node
	 *            The node identifier to filter.
	 * @param user
	 *            The principal user name.
	 * @return The schedules linked to the related node or sub-node.
	 */
	@Query("SELECT ve FROM VmExecution ve INNER JOIN FETCH ve.subscription AS s "
			+ "INNER JOIN s.node AS n WHERE (n.id = :node OR n.id LIKE CONCAT(:node, ':%'))"
			+ " AND ve.id = (SELECT MAX(id) FROM VmExecution WHERE subscription = s)")
	List<VmExecution> findAllByNodeLast(String node);
}
