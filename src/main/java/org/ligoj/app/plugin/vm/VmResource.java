/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.ParseException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.ligoj.app.api.ConfigurablePlugin;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.schedule.VmScheduleResource;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.plugin.AbstractServicePlugin;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.quartz.CronExpression;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The Virtual Machine service.
 */
@Service
@Path(VmResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
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
	protected VmExecutionRepository vmExecutionRepository;

	@Autowired
	protected NodeResource nodeResource;

	@Autowired
	protected ServicePluginLocator locator;

	@Autowired
	protected SecurityHelper securityHelper;

	@Autowired
	protected VmScheduleResource scheduleResource;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

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

	/**
	 * Execute a {@link VmOperation} to the associated VM and checks its visibility against the current principal user.
	 * This a synchronous call, but the effective execution is delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} identifier associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 * @return The execution identifier. Only useful for the correlation. May be <code>null</code> when skipped.
	 */
	@POST
	@Path("{subscription:\\d+}/{operation}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public Integer execute(@PathParam("subscription") final int subscription,
			@PathParam("operation") final VmOperation operation) {
		return execute(subscriptionResource.checkVisible(subscription), operation);
	}

	/**
	 * Execute a {@link VmOperation} to the associated VM. This a synchronous call, but the effective execution is
	 * delayed.
	 * 
	 * @param subscription
	 *            The {@link Subscription} associated to the VM.
	 * @param operation
	 *            the operation to execute.
	 * @return The execution identifier. Only useful for the correlation. May be <code>null</code> when skipped.
	 */
	@Transactional
	public Integer execute(final Subscription subscription, final VmOperation operation) {
		final String node = subscription.getNode().getId();
		final String trigger = securityHelper.getLogin();
		log.info("Operation {} on subscription {}, node {} is requested by {}", operation, subscription.getId(), node,
				trigger);
		final VmExecution execution = new VmExecution();
		execution.setOperation(operation);
		execution.setSubscription(subscription);
		execution.setTrigger(trigger);
		execution.setDate(new Date());
		try {
			// Execute the operation if plug-in still available
			locator.getResourceExpected(node, VmServicePlugin.class).execute(execution);
			log.info("Operation {} (->{}) on subscription {}, node {} : succeed", operation, execution.getOperation(),
					subscription.getId(), node);
			execution.setSucceed(true);
		} catch (final Exception e) {
			// Something goes wrong for this VM, this log would be considered for reporting
			execution.setError(e.getMessage());
			log.error("Operation {} on subscription {}, node {} : failed", operation, subscription.getId(), e);
		} finally {
			// Save as needed
			saveAndFlush(execution, operation);
		}
		return execution.getId();
	}

	/**
	 * Save as needed the given schedule.
	 * 
	 * @param execution
	 *            The execution to persist.
	 * @param operation
	 *            The original operation to execute.
	 */
	private void saveAndFlush(final VmExecution execution, final VmOperation operation) {
		// Check this execution has been really been executed
		if (execution.getOperation() == null) {
			log.info("Operation {} on subscription {} : skipped", operation, execution.getSubscription().getId());
		} else {
			// Persist the execution result
			vmExecutionRepository.saveAndFlush(execution);
		}
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

	/**
	 * Return the execution report of VM related to the given subscription.
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @param file
	 *            The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{subscription:\\d+}/{file:executions-.*.csv}")
	public Response downloadHistoryReport(@PathParam("subscription") final int subscription,
			@PathParam("file") final String file) {
		subscriptionResource.checkVisible(subscription);
		return AbstractToolPluginResource
				.download(o -> writeHistory(o, vmExecutionRepository.findAllBySusbsciption(subscription)), file)
				.build();
	}

	/**
	 * Return all configured schedules report of all VM related to a a visible subscription related to the given node.
	 * 
	 * @param node
	 *            The related node.
	 * @param file
	 *            The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{node:service:.+}/{file:schedules-.*.csv}")
	public Response downloadNodeSchedulesReport(@PathParam("node") final String node,
			@PathParam("file") final String file) {
		// Get all visible schedules linked to this node
		final List<VmSchedule> schedules = vmScheduleRepository.findAllByNode(node, securityHelper.getLogin());

		// Get all last execution related to given node, Key is the subscription identifier
		final Map<Integer, VmExecution> lastExecutions = vmExecutionRepository.findAllByNodeLast(node).stream()
				.collect(Collectors.toMap(e -> e.getSubscription().getId(), Function.identity()));

		// Get all last executions of all schedules
		return AbstractToolPluginResource.download(o -> writeSchedules(o, schedules, lastExecutions), file).build();
	}

	/**
	 * Write all executions related to given subscription, from the oldest to the newest.
	 */
	private void writeHistory(final OutputStream output, Collection<VmExecution> executions) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final FastDateFormat df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		writer.write(
				"subscription;project;projectKey;projectName;node;dateHMS;timestamp;operation;vm;trigger;succeed;statusText;errorText");
		for (final VmExecution execution : executions) {
			writeCommon(writer, execution.getSubscription());
			writeExecutionStatus(writer, execution, df);
		}

		// Ensure buffer is flushed
		writer.flush();
	}

	/**
	 * Write all schedules.
	 */
	private void writeSchedules(final OutputStream output, Collection<VmSchedule> schedules,
			final Map<Integer, VmExecution> lastExecutions) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final FastDateFormat df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		final Date now = DateUtils.newCalendar().getTime();
		writer.write(
				"subscription;project;projectKey;projectName;node;cron;operation;lastDateHMS;lastTimestamp;lastOperation;vm;lastTrigger;lastSucceed;lastStatusText;lastErrorText;nextDateHMS;nextTimestamp");
		for (final VmSchedule schedule : schedules) {
			// The last execution of the related schedule
			final VmExecution execution = lastExecutions.get(schedule.getSubscription().getId());
			writeCommon(writer, schedule.getSubscription());
			writer.write(';');
			writer.write(schedule.getCron());
			writer.write(';');
			writer.write(schedule.getOperation().name());
			if (execution == null) {
				writer.write(";;;;;;;;");
			} else {
				// Last execution
				writeExecutionStatus(writer, execution, df);
			}

			// Next execution
			try {
				final Date next = new CronExpression(schedule.getCron()).getNextValidTimeAfter(now);
				writer.write(';');
				writer.write(df.format(next));
				writer.write(';');
				writer.write(String.valueOf(next.getTime()));
			} catch (final ParseException pe) {
				// Non blocking error
				log.error("Invalid CRON expression {} : {}", schedule.getCron(), pe.getMessage());
				writer.write(";ERROR;ERROR");
			}
		}

		// Ensure buffer is flushed
		writer.flush();
	}

	/**
	 * Write <code>dateHms;timestamp;operation;vm;trigger;succeed;statusText;errorText</code> execution values.
	 * 
	 * @param writer
	 *            Target output.
	 * @param execution
	 *            Execution to write.
	 * @param df
	 *            Date format for date to write.
	 */
	private void writeExecutionStatus(final Writer writer, final VmExecution execution, final FastDateFormat df)
			throws IOException {
		writer.write(';');
		writer.write(df.format(execution.getDate()));
		writer.write(';');
		writer.write(String.valueOf(execution.getDate().getTime()));
		writer.write(';');
		writer.write(execution.getOperation().name());
		writer.write(';');
		writer.write(StringUtils.defaultString(execution.getVm(), ""));
		writer.write(';');
		writer.write(execution.getTrigger());
		writer.write(';');
		writer.write(String.valueOf(execution.isSucceed()));
		writer.write(';');
		writer.write(StringUtils.defaultString(execution.getStatusText(), ""));
		writer.write(';');
		writer.write(StringUtils.defaultString(execution.getError(), ""));
	}

	/**
	 * Write <code>subscription;project;projetKey;projectName;node</code>.
	 * 
	 * @param writer
	 *            Target output.
	 * @param subscription
	 *            Related subscription.
	 */
	private void writeCommon(final Writer writer, final Subscription subscription) throws IOException {
		final Project project = subscription.getProject();
		writer.write('\n');
		writer.write(String.valueOf(subscription.getId()));
		writer.write(';');
		writer.write(String.valueOf(project.getId()));
		writer.write(';');
		writer.write(project.getPkey());
		writer.write(';');
		writer.write(project.getName().replaceAll("\"", "'"));
		writer.write(';');
		writer.write(subscription.getNode().getId());
	}
}
