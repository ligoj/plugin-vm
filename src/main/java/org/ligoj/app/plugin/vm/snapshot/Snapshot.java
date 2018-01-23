package org.ligoj.app.plugin.vm.snapshot;

import java.util.Date;
import java.util.List;

import org.ligoj.app.iam.UserOrg;
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
	 * User requesting this snapshot. May be <code>null</code> when system requested
	 * it.
	 */
	private UserOrg author;

	/**
	 * Creation date.
	 */
	private Date date;

	/**
	 * The volume snapshots.
	 */
	private List<VolumeSnapshot> volumes;

	private boolean available;

	private boolean pending;

	/**
	 * When <code>true</code>, this snapshot has been created from a stopped VM.
	 * This information is not saved with the AMI and is only relevant and not
	 * <code>null</code> with "pending" state set from the task runner.
	 */
	private Boolean stopRequested;

	private String statusText;
}
