/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

import java.util.Date;
import java.util.List;

import org.ligoj.app.iam.SimpleUser;
import org.ligoj.app.plugin.vm.model.SnapshotOperation;
import org.ligoj.bootstrap.core.DescribedBean;

import lombok.Getter;
import lombok.Setter;

/**
 * A VM snapshot.
 */
@Getter
@Setter
public class Snapshot extends DescribedBean<String> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * User requesting this snapshot. May be <code>null</code> when system requested it.
	 */
	private SimpleUser author;

	/**
	 * Creation date.
	 */
	private Date date;

	/**
	 * The volume snapshots.
	 */
	private List<VolumeSnapshot> volumes;

	/**
	 * The remote state given by the provider. The snapshot is technically completed, and is available. However, some
	 * provider may delay the globally availability : replication, cache etc.
	 */
	private boolean available;

	/**
	 * Differs from the {@link #isAvailable()} when the snapshot is available from the provider side, but not yet
	 * visible/available at client side.
	 */
	private boolean pending;

	/**
	 * The current snapshot operation.
	 */
	private SnapshotOperation operation;

	/**
	 * When <code>true</code>, this snapshot has been created from a stopped VM. This information is not saved with the
	 * AMI and is only relevant and not <code>null</code> with "pending" state set from the task runner.
	 */
	private Boolean stopRequested;

	/**
	 * Current status either collected at server side, either client side when there are several phases required to complete the task.
	 */
	private String statusText;
}
