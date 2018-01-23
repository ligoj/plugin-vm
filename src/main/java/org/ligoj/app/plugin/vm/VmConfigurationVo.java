package org.ligoj.app.plugin.vm;

import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;

import lombok.Getter;
import lombok.Setter;

/**
 * VM configuration.
 */
@Getter
@Setter
public class VmConfigurationVo {

	/**
	 * Optional schedules for this VM
	 */
	@Valid
	private List<VmScheduleVo> schedules = new ArrayList<>();

	/**
	 * When <code>true</code> the related tool supports snapshot.
	 */
	boolean supportSnapshot;
}
