/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.ligoj.app.model.AbstractLongTaskSubscription;
import org.ligoj.app.plugin.vm.execution.Vm;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

/**
 * A VM operation execution.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_VM_EXECUTION_STATUS")
public class VmExecutionStatus extends AbstractLongTaskSubscription {

	/**
	 * A flag indicating the remote status. This flag complete the "finished" flag. All instruction have been sent, no
	 * more API call to do, and yet the resource is not yet available because of the asynchronous execution. When
	 * <code>true</code>, this is global availability. Otherwise, when {@link #isFinished()} is <code>true</code>, the
	 * API have been sent but not completed.
	 */
	private boolean finishedRemote;

	/**
	 * The related real execution operation.
	 */
	@Enumerated(EnumType.STRING)
	private VmOperation operation;

	/**
	 * Optional attached VM status.
	 */
	@JsonIgnore
	@Transient
	private Vm vm;

	/**
	 * Optional attached execution order.
	 */
	@JsonIgnore
	@Transient
	private VmExecution execution;
}
