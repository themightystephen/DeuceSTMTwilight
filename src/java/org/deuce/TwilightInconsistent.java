package org.deuce;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Used to mark the method which is run in the case when the stmPrepare() operation finds that
 * the readset is inconsistent (i.e. the readset is inconsistent upon entering the twilight zone).
 *
 * @author stephen
 */
@Target(METHOD)
@Retention(CLASS)
public @interface TwilightInconsistent {
	String name();
}
