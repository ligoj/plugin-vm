/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

/**
 * Snapshot status.
 */
public enum SnapshotStatus {

	/**
	 * Snapshot is available and finished.
	 */
	AVAILABLE,
	
	/**
	 * Snapshot is pending.
	 */
	PENDING,
	
	/**
	 * Snapshot is finished, and whit error.
	 */
	ERROR
}
