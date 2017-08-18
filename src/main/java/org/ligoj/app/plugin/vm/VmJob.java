package org.ligoj.app.plugin.vm;

import java.text.MessageFormat;
import java.text.ParseException;
import java.text.ParsePosition;

import org.apache.commons.lang3.ObjectUtils;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.TriggerKey;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * VM Service job executing operations.
 */
public class VmJob extends QuartzJobBean {

	/**
	 * {@link TriggerKey} formatter containing subscription and
	 * {@link VmOperation} : <code>subscription-operation.name</code>
	 */
	private static final String TRIGGER_ID_PARSER = "{0,number,integer}-{1}";

	@Override
	protected void executeInternal(final JobExecutionContext arg0) throws JobExecutionException {
		// Extract the job data to execute the operation
		final VmOperation operation = VmOperation.valueOf(arg0.getMergedJobDataMap().getString("operation"));
		final int subscription = arg0.getMergedJobDataMap().getInt("subscription");
		final ApplicationContext context = ObjectUtils.defaultIfNull((ApplicationContext) arg0.getMergedJobDataMap().get("context"),
				SpringUtils.getApplicationContext());
		final Subscription entity = context.getBean(SubscriptionRepository.class).findOneExpected(subscription);

		// Set the user
		context.getBean(SecurityHelper.class).setUserName(SecurityHelper.SYSTEM_USERNAME);

		// Execute the operation
		context.getBean(VmResource.class).execute(entity, operation);
	}

	/**
	 * Build and return the trigger identifier from the subscription and the
	 * operation.
	 * 
	 * @param subscription
	 *            The subscription identifier.
	 * @param operation
	 *            The operation to execute.
	 * @return the {@link String} identifier for the trigger.
	 */
	protected static String format(final int subscription, final VmOperation operation) {
		return subscription + "-" + operation.name();
	}

	/**
	 * Extract the subscription identifier from the trigger
	 * 
	 * @param key
	 *            the {@link TriggerKey}
	 * @return the subscription identifier.
	 */
	protected static int getSubscription(final TriggerKey key) {
		return ((Long) parse(key.getName())[0]).intValue();
	}

	/**
	 * Extract the operation from the trigger
	 * 
	 * @param key
	 *            the {@link TriggerKey}
	 * @return the operation.
	 */
	protected static VmOperation getOperation(final TriggerKey key) {
		return VmOperation.valueOf(parse(key.getName())[1].toString());
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
		} catch (final ParseException e) {
			// Ignore the parse error
			return new Object[2];
		}
	}

}
