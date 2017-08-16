package org.ligoj.app.plugin.vm.model;

import java.util.Date;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
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
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Execution date
	 */
	@NotNull
	private Date date;

	/**
	 * The executed VM operation
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
	 * The trigger mode, either <code>null</code> when scheduled, either the
	 * principal identifier.
	 */
	@NotNull
	private String trigger;

	/**
	 * The error message. <code>null</code> when succeed.
	 */
	private String error;

}
