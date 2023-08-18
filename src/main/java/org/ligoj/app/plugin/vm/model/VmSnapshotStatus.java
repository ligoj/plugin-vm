/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.model;


import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.ligoj.app.model.AbstractLongTaskSubscription;

/**
 * A snapshot VM operation.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_VM_SNAPSHOT_STATUS")
public class VmSnapshotStatus extends AbstractLongTaskSubscription {

	/**
	 * The status text used for error.
	 */
	private String statusText;

	private int workload;
	private int done;
	private String phase;

	/**
	 * A flag indicating the remote status. This flag complete the "finished" flag. All instruction have been sent, no
	 * more API call to do, and yet the resource is not yet available because of the asynchronous execution. When
	 * <code>true</code>, this is global availability. Otherwise, when {@link #isFinished()} is <code>true</code>, the
	 * API have been sent but not completed.
	 */
	private boolean finishedRemote;

	/**
	 * When <code>true</code>, the VM need to be stopped before the snapshot.
	 */
	private boolean stop;

	/**
	 * The related provider snapshot identifier.
	 */
	private String snapshotInternalId;

	/**
	 * The associated snapshot operation.
	 */
	@Enumerated(EnumType.STRING)
	private SnapshotOperation operation = SnapshotOperation.CREATE;

}
