package org.deuce.test.twilight.ex2;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Used to mark the method which is run in the case when the stmPrepare() operation finds that
 * the readset is consistent (i.e. the readset is consistent upon entering the twilight zone).
 *
 * @author stephen
 */
@Target(METHOD)
@Retention(CLASS)
public @interface TwilightConsistent {
	String name();
}
