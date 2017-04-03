package org.ligoj.app.plugin.vm;

import org.ligoj.app.api.ServicePlugin;
import org.ligoj.app.plugin.vm.model.VmOperation;

/**
 * Features of VM implementations.
 */
public interface VmServicePlugin extends ServicePlugin {

	/**
	 * Execute a VmOperation to the associated VM. If a virtual machine is writing to disk when it receives a Power Off
	 * command, data
	 * corruption may occur.
	 * 
	 * @param subscription
	 *            the subscription's identifier.
	 * @param operation
	 *            The operation to execute.
	 * @throws Exception
	 *             Any exception while executing the operation.
	 */
	void execute(int subscription, VmOperation operation) throws Exception;
}
