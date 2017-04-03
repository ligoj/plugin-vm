package org.ligoj.app.plugin.vm;

import javax.validation.constraints.NotNull;

import org.ligoj.app.plugin.vm.model.VmOperation;

import lombok.Getter;
import lombok.Setter;

/**
 * A schedule for an operation on the VM associated to the subscription.
 */
@Getter
@Setter
public class VmScheduleVo {

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
	
}
