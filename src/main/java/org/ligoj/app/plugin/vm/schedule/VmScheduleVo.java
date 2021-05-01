/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.schedule;

import java.util.Date;

import javax.validation.constraints.NotEmpty;
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
	 * Optional identifier.
	 */
	private Integer id;

	/**
	 * CRON expression for this schedule
	 */
	@NotNull
	@NotEmpty
	private String cron;

	/**
	 * The scheduled VM operation
	 */
	@NotNull
	private VmOperation operation;

	/**
	 * The next scheduled execution date from the server side.
	 */
	private Date next;

}
