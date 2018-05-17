/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm;

import java.text.ParseException;

import javax.transaction.Transactional;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.api.ConfigurablePlugin;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.schedule.VmScheduleResource;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.plugin.AbstractServicePlugin;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Virtual Machine service.
 */
@Service
@Path(VmResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class VmResource extends AbstractServicePlugin implements ConfigurablePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = BASE_URL + "/vm";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_KEY = SERVICE_URL.replace('/', ':').substring(1);

	@Autowired
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected ServicePluginLocator locator;

	@Autowired
	protected VmScheduleResource scheduleResource;

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	@Override
	@Transactional
	public void delete(final int subscription, final boolean deleteRemoteData) throws SchedulerException {
		// Also remove execution history
		scheduleResource.delete(subscription);
	}

	@GET
	@Path("{subscription:\\d+}")
	@Override
	@Transactional
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public VmConfigurationVo getConfiguration(@PathParam("subscription") final int subscription) throws ParseException {
		// Check the subscription is visible
		final Subscription entity = subscriptionResource.checkVisible(subscription);

		// Get the details
		final VmConfigurationVo result = new VmConfigurationVo();
		result.setSchedules(scheduleResource.findAll(subscription));

		// Add snapshot capability
		result.setSupportSnapshot(locator.getResource(entity.getNode().getId(), Snapshotting.class) != null);
		return result;
	}
}
