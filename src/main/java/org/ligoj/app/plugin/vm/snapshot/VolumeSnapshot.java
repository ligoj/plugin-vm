/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.snapshot;

import org.ligoj.bootstrap.core.NamedBean;

import lombok.Getter;
import lombok.Setter;

/**
 * A volume snapshot. The <code>name</code> attribute corresponds to the device name.
 */
@Getter
@Setter
public class VolumeSnapshot extends NamedBean<String> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * GiB volume size
	 */
	private int size;

}
