/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm;

import java.util.Map;

import org.ligoj.app.api.ServicePlugin;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;

/**
 * Features of VM implementations.
 */
public interface VmServicePlugin extends ServicePlugin {

	/**
	 * Execute a VmOperation to the associated VM. If a virtual machine is writing to disk when it receives a Power Off
	 * command, data corruption may occur.
	 * 
	 * @param subscription
	 *            the subscription's identifier.
	 * @param operation
	 *            The operation to execute.
	 * @throws Exception
	 *             Any exception while executing the operation.
	 * @deprecated Use {@link #execute(VmExecution)}, it contains all information and can complete the execution
	 *             context.
	 */
	@Deprecated(since = "1.3.1")
	default void execute(int subscription, VmOperation operation) throws Exception {
		// nothing to do
	}

	/**
	 * Get the VM configuration.
	 * 
	 * @param parameters
	 *            the subscription parameters.
	 * @return Virtual Machine details with status, PU, and RAM.
	 * @throws Exception
	 *             When details failed, the subscription is considered as broken.
	 */
	Vm getVmDetails(final Map<String, String> parameters) throws Exception;

	/**
	 * Execute the given execution. The current execution context can be completed : VM identifier or "statusText".
	 * 
	 * @param execution
	 *            The current execution. Not this execution ay be updated by the implementor, and real proceeded
	 *            operation can be altered. When real operation is set to <code>null</code>, the execution is considered
	 *            as skipped.
	 * @throws Exception
	 *             Any exception while executing the operation.
	 * @see #execute(int, VmOperation)
	 * @since 1.3.1
	 */
	default void execute(final VmExecution execution) throws Exception {
		execute(execution.getSubscription().getId(), execution.getOperation());
	}
}
