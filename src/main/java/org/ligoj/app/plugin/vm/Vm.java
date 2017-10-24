package org.ligoj.app.plugin.vm;

import java.util.List;

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

	/**
	 * Networks of this VM
	 */
	private List<VmNetwork> networks;

	private boolean busy;

	/**
	 * When <code>true</code>, the VM is consuming (reserved) compute resources.
	 */
	private boolean deployed;
	private String os;
}
