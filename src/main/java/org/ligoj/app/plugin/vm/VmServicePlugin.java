package org.ligoj.app.plugin.vm;

import java.util.Map;

import org.ligoj.app.api.ServicePlugin;
import org.ligoj.app.plugin.vm.model.VmOperation;

/**
 * Features of VM implementations.
 */
public interface VmServicePlugin extends ServicePlugin {

	/**
	 * Execute a VmOperation to the associated VM. If a virtual machine is
	 * writing to disk when it receives a Power Off command, data corruption may
	 * occur.
	 * 
	 * @param subscription
	 *            the subscription's identifier.
	 * @param operation
	 *            The operation to execute.
	 * @throws Exception
	 *             Any exception while executing the operation.
	 */
	void execute(int subscription, VmOperation operation) throws Exception;

	/**
	 * Get the VM configuration.
	 * 
	 * @param parameters
	 *            the subscription parameters.
	 * @return Virtual Machine details with status, PU, and RAM.
	 */
	Vm getVmDetails(final Map<String, String> parameters) throws Exception;
}
