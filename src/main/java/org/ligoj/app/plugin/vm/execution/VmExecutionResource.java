/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.execution;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.FastDateFormat;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmExecutionRepository;
import org.ligoj.app.plugin.vm.dao.VmExecutionStatusRepository;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.*;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.subscription.LongTaskRunnerSubscription;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.text.ParseException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * VM Execution task runner.
 */
@Slf4j
@Service
@Path(VmResource.SERVICE_URL + "/{subscription:\\d+}/execution")
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public class VmExecutionResource implements LongTaskRunnerSubscription<VmExecutionStatus, VmExecutionStatusRepository> {

	private static final String COMMON_CSV_HEADER = "subscription;project;projectKey;projectName;node";

	@Autowired
	protected VmExecutionResource self = this;

	@Autowired
	@Getter
	protected VmExecutionStatusRepository taskRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	@Getter
	protected SubscriptionRepository subscriptionRepository;

	@Autowired
	@Getter
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected VmExecutionRepository vmExecutionRepository;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	protected ServicePluginLocator locator;

	/**
	 * Execute a {@link VmOperation} to the associated VM and checks its visibility against the current principal user.
	 * This a synchronous call, but the effective execution is delayed.
	 *
	 * @param subscription The {@link Subscription} identifier associated to the VM.
	 * @param operation    the operation to execute.
	 * @return The execution task information.
	 */
	@POST
	@Path("{operation}")
	@Consumes(MediaType.APPLICATION_JSON)
	public VmExecutionStatus execute(@PathParam("subscription") final int subscription,
			@PathParam("operation") final VmOperation operation) {
		return execute(subscriptionResource.checkVisible(subscription), operation);
	}

	/**
	 * Execute a {@link VmOperation} to the associated VM. This is a synchronous call, but the effective execution is
	 * delayed.
	 *
	 * @param subscription The {@link Subscription} associated to the VM.
	 * @param operation    the operation to execute.
	 * @return The execution task information.
	 */
	public VmExecutionStatus execute(final Subscription subscription, final VmOperation operation) {
		final var node = subscription.getNode().getId();
		final var trigger = securityHelper.getLogin();
		log.info("Operation {} on subscription {}, node {} is requested by {}", operation, subscription.getId(), node,
				trigger);
		final var execution = new VmExecution();
		var failed = true;
		execution.setOperation(operation);
		execution.setSubscription(subscription);
		execution.setTrigger(trigger);
		execution.setDate(new Date());
		final var task = self.startTask(subscription.getId(), t -> {
			t.setFinishedRemote(false);
			t.setOperation(operation);
			// Share the current execution, this relationship is not persisted
			t.setExecution(execution);
		});
		try {
			// Execute the operation if plug-in still available
			getTool(node).execute(execution);
			log.info("Operation {} (->{}) on subscription {}, node {} : succeed", operation, execution.getOperation(),
					subscription.getId(), node);
			execution.setSucceed(true);
			failed = false;
		} catch (final Exception e) {
			// Something goes wrong for this execution, this log would be considered for reporting
			execution.setError(e.getMessage());
			log.error("Operation {} on subscription {}, node {} : failed", operation, subscription.getId(), node, e);
		} finally {
			// Save the history as needed
			self.endTask(subscription.getId(), failed);
			saveAndFlush(execution, operation);
		}
		return task;
	}

	@Override
	public Supplier<VmExecutionStatus> newTask() {
		return VmExecutionStatus::new;
	}

	@Override
	@GET
	public VmExecutionStatus getTask(@PathParam("subscription") final int subscription) {
		final var task = LongTaskRunnerSubscription.super.getTask(subscription);
		if (task != null && completeStatus(task)) {
			// Save the new state
			taskRepository.saveAndFlush(task);
		}
		return task;
	}

	private VmExecutionServicePlugin getTool(final String node) {
		return locator.getResourceExpected(node, VmExecutionServicePlugin.class);
	}

	private boolean completeStatus(final VmExecutionStatus task) {
		if (task.isFailed()) {
			task.setFinishedRemote(true);
		}
		if (!task.isFinishedRemote() && task.isFinished()) {
			// Complete the status for the uncompleted tasks
			final int subscription = task.getLocked().getId();
			final var node = task.getLocked().getNode().getId();
			try {
				final var vm = getTool(node).getVmDetails(subscriptionResource.getParametersNoCheck(subscription));
				task.setVm(vm);
				task.setFinishedRemote(!vm.isBusy());
				return true;
			} catch (final Exception e) {
				// Unable to get the VM details
				log.info("Unable to retrieve VM information of subscription {}, node {}", subscription, node);
			}
		}
		return false;
	}

	@Override
	public boolean isFinished(final VmExecutionStatus task) {
		completeStatus(task);
		return task.isFinishedRemote();
	}

	/**
	 * Return the execution report of VM related to the given subscription.
	 *
	 * @param subscription The related subscription.
	 * @param file         The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path("{file:executions-.*.csv}")
	public Response downloadHistoryReport(@PathParam("subscription") final int subscription,
			@PathParam("file") final String file) {
		subscriptionResource.checkVisible(subscription);
		return AbstractToolPluginResource
				.download(o -> writeHistory(o, vmExecutionRepository.findAllBySubscription(subscription)), file)
				.build();
	}

	/**
	 * Return all configured schedules report of all VM related to a visible subscription related to the given node.
	 *
	 * @param node The related node.
	 * @param file The requested file name.
	 * @return The download stream.
	 */
	@GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@Path(VmResource.SERVICE_URL + "/{node:service:.+}/{file:schedules-.*.csv}")
	public Response downloadNodeSchedulesReport(@PathParam("node") final String node,
			@PathParam("file") final String file) {
		// Get all visible schedules linked to this node
		final var schedules = vmScheduleRepository.findAllByNode(node, securityHelper.getLogin());

		// Get all last execution related to given node, Key is the subscription identifier
		final var lastExecutions = vmExecutionRepository.findAllByNodeLast(node).stream()
				.collect(Collectors.toMap(e -> e.getSubscription().getId(), Function.identity()));

		// Get all last executions of all schedules
		return AbstractToolPluginResource.download(o -> writeSchedules(o, schedules, lastExecutions), file).build();
	}

	/**
	 * Write all executions related to given subscription, from the oldest to the newest.
	 */
	private void writeHistory(final OutputStream output, Collection<VmExecution> executions) throws IOException {
		final var writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final var df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		writer.write(COMMON_CSV_HEADER
				+ ";dateHMS;timestamp;previousState;operation;vm;trigger;succeed;statusText;errorText");
		for (final var execution : executions) {
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
		final var writer = new BufferedWriter(new OutputStreamWriter(output, "cp1252"));
		final var df = FastDateFormat.getInstance("yyyy/MM/dd HH:mm:ss");
		final var now = DateUtils.newCalendar().getTime();
		writer.write(COMMON_CSV_HEADER
				+ ";cron;operation;lastDateHMS;lastTimestamp;previousState;lastOperation;vm;lastTrigger;lastSucceed;lastStatusText;lastErrorText;nextDateHMS;nextTimestamp");
		for (final VmSchedule schedule : schedules) {
			// The last execution of the related schedule
			final var execution = lastExecutions.get(schedule.getSubscription().getId());
			writeCommon(writer, schedule.getSubscription());
			writer.write(';');
			writer.write(schedule.getCron());
			writer.write(';');
			writer.write(schedule.getOperation().name());
			if (execution == null) {
				writer.write(";;;;;;;;;");
			} else {
				// Last execution
				writeExecutionStatus(writer, execution, df);
			}

			// Next execution
			try {
				final var next = new CronExpression(schedule.getCron()).getNextValidTimeAfter(now);
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
	 * Write <code>dateHms;timestamp;previousState;operation;vm;trigger;succeed;statusText;errorText</code> execution
	 * values.
	 *
	 * @param writer    Target output.
	 * @param execution Execution to write.
	 * @param df        Date format for date to write.
	 */
	private void writeExecutionStatus(final Writer writer, final VmExecution execution, final FastDateFormat df)
			throws IOException {
		writer.write(';');
		writer.write(df.format(execution.getDate()));
		writer.write(';');
		writer.write(String.valueOf(execution.getDate().getTime()));
		writer.write(';');
		writer.write(Optional.ofNullable(execution.getPreviousState()).map(VmStatus::name).orElse(""));
		writer.write(';');
		writer.write(execution.getOperation().name());
		writer.write(';');
		writer.write(Objects.toString(execution.getVm(), ""));
		writer.write(';');
		writer.write(execution.getTrigger());
		writer.write(';');
		writer.write(String.valueOf(execution.isSucceed()));
		writer.write(';');
		writer.write(Objects.toString(execution.getStatusText(), ""));
		writer.write(';');
		writer.write(Objects.toString(execution.getError(), ""));
	}

	/**
	 * Write <code>subscription;project;projectKey;projectName;node</code>.
	 *
	 * @param writer       Target output.
	 * @param subscription Related subscription.
	 */
	private void writeCommon(final Writer writer, final Subscription subscription) throws IOException {
		final var project = subscription.getProject();
		writer.write('\n');
		writer.write(String.valueOf(subscription.getId()));
		writer.write(';');
		writer.write(String.valueOf(project.getId()));
		writer.write(';');
		writer.write(project.getPkey());
		writer.write(';');
		writer.write(project.getName().replace('\"', '\''));
		writer.write(';');
		writer.write(subscription.getNode().getId());
	}

	/**
	 * Save as needed the given schedule.
	 *
	 * @param execution The execution to persist.
	 * @param operation The original operation to execute.
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
}
