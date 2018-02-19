package org.ligoj.app.plugin.vm.model;

import java.util.Date;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.validation.constraints.NotNull;

import org.ligoj.app.model.Subscription;
import org.ligoj.bootstrap.core.model.AbstractPersistable;

import lombok.Getter;
import lombok.Setter;

/**
 * An executed (succeed or failed) VM operation.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_VM_EXECUTION")
public class VmExecution extends AbstractPersistable<Integer> {

	/**
	 * Execution date
	 */
	@NotNull
	@Temporal(TemporalType.DATE)
	private Date date;

	/**
	 * The executed VM operation. When <code>null</code> in detached mode, the execution is cancelled.
	 */
	@NotNull
	private VmOperation operation;

	/**
	 * The associated subscription
	 */
	@NotNull
	@ManyToOne
	private Subscription subscription;

	/**
	 * The truncated result
	 */
	private boolean succeed;

	/**
	 * The trigger mode, either <code>null</code> when scheduled, either the principal identifier.
	 */
	@NotNull
	private String trigger;

	/**
	 * The error message. <code>null</code> when succeed.
	 */
	private String error;

	/**
	 * The related VM identifier when the execution has been proceeded. May be <code>null</code> when an error occurs
	 * before the resolution of the related plug-in implementor.
	 */
	private String vm;

	/**
	 * The optional status text.
	 */
	private String statusText;

}
