/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.execution;

import java.util.Map;

import org.ligoj.app.api.ServicePlugin;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;

/**
 * Features of VM implementations.
 */
public interface VmExecutionServicePlugin extends ServicePlugin {

	/**
	 * Get the VM configuration.
	 *
	 * @param parameters the subscription parameters.
	 * @return Virtual Machine details with status, PU, and RAM.
	 * @throws Exception When details failed, the subscription is considered as broken.
	 */
	Vm getVmDetails(final Map<String, String> parameters) throws Exception; // NOSONAR

	/**
	 * Execute the given execution. The current execution context can be completed : VM identifier or "statusText".
	 *
	 * @param execution The current execution. Not this execution ay be updated by the implementor, and real proceeded
	 *                  operation can be altered. When real operation is set to <code>null</code>, the execution is
	 *                  considered as skipped.
	 * @throws Exception Any exception while executing the operation.
	 * @see #execute(int, VmOperation)
	 * @since 1.3.1
	 */
	default void execute(final VmExecution execution) throws Exception {
		// nothing to do
	}
}
