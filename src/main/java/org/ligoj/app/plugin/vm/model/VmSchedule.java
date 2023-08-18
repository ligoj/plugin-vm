/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.model;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.ligoj.app.model.Subscription;
import org.ligoj.bootstrap.core.model.AbstractPersistable;

/**
 * A scheduled VM operation.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_VM_SCHEDULE")
public class VmSchedule extends AbstractPersistable<Integer> {

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
