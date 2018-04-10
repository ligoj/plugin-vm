/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.schedule;

import java.text.MessageFormat;
import java.text.ParseException;
import java.text.ParsePosition;

import org.apache.commons.lang3.ObjectUtils;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.quartz.JobExecutionContext;
import org.quartz.TriggerKey;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

import lombok.extern.slf4j.Slf4j;

/**
 * VM Service job executing operations.
 */
@Slf4j
public class VmJob extends QuartzJobBean {

	/**
	 * {@link TriggerKey} formatter containing schedule identifier and
	 * subscription identifier. Format is "SCHEDULE-SUBSCRIPTION".
	 */
	private static final String TRIGGER_ID_PARSER = "{0,number,integer}-{1,number,integer}";

	@Override
	protected void executeInternal(final JobExecutionContext arg0) {
		// Extract the job data to execute the operation
		final int schedule = arg0.getMergedJobDataMap().getInt("schedule");
		final ApplicationContext context = ObjectUtils.defaultIfNull((ApplicationContext) arg0.getMergedJobDataMap().get("context"),
				SpringUtils.getApplicationContext());
		final VmSchedule entity = context.getBean(VmScheduleRepository.class).findOneExpected(schedule);
		log.info("Executing {} for schedule {}, subscription {}", entity.getOperation(), entity.getId(), entity.getSubscription().getId());

		// Set the user
		context.getBean(SecurityHelper.class).setUserName(SecurityHelper.SYSTEM_USERNAME);

		// Execute the operation
		context.getBean(VmResource.class).execute(entity.getSubscription(), entity.getOperation());
		log.info("Succeed {} for schedule {}, subscription {}", entity.getOperation(), entity.getId(), entity.getSubscription().getId());
	}

	/**
	 * Build and return the trigger identifier from the schedule and the
	 * subscription.
	 * 
	 * @param schedule
	 *            The schedule entity.
	 * @return the {@link String} identifier for the trigger.
	 */
	protected static String format(final VmSchedule schedule) {
		return schedule.getId() + "-" + schedule.getSubscription().getId();
	}

	/**
	 * Extract the schedule identifier from the trigger
	 * 
	 * @param key
	 *            the {@link TriggerKey}
	 * @return the subscription identifier.
	 */
	protected static int getSchedule(final TriggerKey key) {
		return ((Long) parse(key.getName())[0]).intValue();
	}

	/**
	 * Extract the subscription identifier from the trigger
	 * 
	 * @param key
	 *            the {@link TriggerKey}
	 * @return the subscription identifier.
	 */
	protected static int getSubscription(final TriggerKey key) {
		return ((Long) parse(key.getName())[1]).intValue();
	}

	/**
	 * Parses text from the beginning of the given string to produce an object
	 * array. The method may not use the entire text of the given string.
	 * <p>
	 * See the {@link MessageFormat#parse(String, ParsePosition)} method for
	 * more information on message parsing.
	 *
	 * @param source
	 *            A <code>String</code> whose beginning should be parsed.
	 * @return An <code>Object</code> array parsed from the string.
	 *         ParseException is caught to return an 2 sized array object.
	 */
	protected static Object[] parse(final String source) {
		try {
			return new MessageFormat(TRIGGER_ID_PARSER).parse(source);
		} catch (@SuppressWarnings("unused") final ParseException e) {
			// Ignore the parse error
			return new Object[2];
		}
	}

}
