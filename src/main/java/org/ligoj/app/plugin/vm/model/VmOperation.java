/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.model;

/**
 * VM operation to execute.
 */
public enum VmOperation {

	/**
	 * Power Off the associated VM. If a virtual machine is writing to disk when it receives a Power Off command, data
	 * corruption may occur.
	 */
	OFF,
	/**
	 * Start (Power On) or resume the associated VM
	 */
	ON,

	/**
	 * Stop gracefully the associated VM. Not all guest operating systems respond to a shut-down signal from this
	 * button. If your operating system does not respond to a shut-down signal, shut down from within the operating
	 * system, as you would with a physical machine.
	 */
	SHUTDOWN,

	/**
	 * Reset the associated VM.As with physical computers, you may need to reset a guest operating system that has
	 * become unresponsive. This is generally not recommended: If you reset a virtual machine while the virtual disk is
	 * being written to, data may be lost or corrupted.
	 */
	RESET,

	/**
	 * Restart the associated VM. Not all guest operating systems respond to a restart signal from this button. If your
	 * operating system does not respond to a restart signal, restart from within the operating system, as you would
	 * with a physical machine.
	 */
	REBOOT,

	/**
	 * Pause the associated VM
	 */
	SUSPEND
}
