package org.ligoj.app.plugin.vm;

import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.bootstrap.core.DescribedBean;

import lombok.Getter;
import lombok.Setter;

/**
 * Virtual machine description.
 */
@Getter
@Setter
public class Vm extends DescribedBean<String> {
	private VmStatus status;

	/**
	 * Amount of CPUs.
	 */
	private int cpu;

	/**
	 * Memory, MB.
	 */
	private int ram;

	private boolean busy;
	private boolean deployed;
	private String os;
}