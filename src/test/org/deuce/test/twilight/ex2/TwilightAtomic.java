package org.deuce.test.twilight.ex2;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks methods which are the main transactional/atomic part of a Twilight-style transaction.
 * The name attribute is required; it is a String used to identify and link the
 * transactional/atomic method of the transaction with the other two methods which
 * form part of the Twilight transaction, marked with @TwilightConsistent and
 * @TwilightInconsistent (respectively).
 *
 * @author Stephen Tuttlebee
 * @since 1.4???
 */
@Target(METHOD)
@Retention(CLASS)
public @interface TwilightAtomic {
	String name();
	int retries() default Integer.MAX_VALUE;
	String metainf() default "";
}
