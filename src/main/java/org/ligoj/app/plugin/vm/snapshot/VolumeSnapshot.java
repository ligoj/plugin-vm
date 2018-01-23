package org.ligoj.app.plugin.vm.snapshot;

import org.ligoj.bootstrap.core.NamedBean;

import lombok.Getter;
import lombok.Setter;

/**
 * A volume snapshot. The <code>name</code> attribute corresponds to the device
 * name.
 */
@Getter
@Setter
public class VolumeSnapshot extends NamedBean<String> {

	/**
	 * GiB volume size
	 */
	private int size;

}
