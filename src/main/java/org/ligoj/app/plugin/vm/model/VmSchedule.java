package org.ligoj.app.plugin.vm.model;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;

import org.ligoj.app.model.Subscription;
import org.ligoj.bootstrap.core.model.AbstractPersistable;

import lombok.Getter;
import lombok.Setter;

/**
 * A scheduled VM operation.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_VM_SCHEDULE")
public class VmSchedule extends AbstractPersistable<Integer> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 4795855466011388616L;

	/**
	 * CRON expression for this schedule
	 */
	@NotNull
	private String cron;

	/**
	 * The scheduled VM operation
	 */
	@NotNull
	private VmOperation operation;

	/**
	 * The associated subscription
	 */
	@NotNull
	@ManyToOne
	private Subscription subscription;

}
