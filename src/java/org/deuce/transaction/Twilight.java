package org.deuce.transaction;

/**
 * API for programmers to directly access operations of TwilightContext class.
 * In non-twilight transactions, there was no requirement for such an API because the
 * only operations needed are init(), commit(), and the field access methods
 * onReadAccess(), beforeReadAccess(), and onWriteAccess(). The places the calls to these
 * should go is pre-determined (beginning, end and on field accesses, respectively).
 *
 * For Twilight operations, functionality needs to be available to programmers at any
 * time. For example, tagging/region operations could be invoked at points during the
 * transactional part (@TwilightAtomic) [TODO: check if new tags and marking of fields with
 * tags is only allowed during transactional part of transaction and not in twilight zone].
 * Additionally, repair operations such as consistency w.r.t. a tag/region and ignoring
 * inconsistencies should be available to be invoked at any time by the programmer
 * (provided its inside the twilight zone).
 *
 * Where methods should be called by user
 * ======================================
 * reload (twilight zone; inconsistent)
 * ignoreUpdates (twilight zone; inconsistent)
 * isInconsistent (twilight zone; inconsistent)
 * isOnlyInconsistent (twilight zone; inconsistent)
 * newTag (transactional zone)
 * markField (transactional zone)
 *
 * The above categorisation makes it clear that all the repair operations are only allowed in the @TwilightInconsistent
 * method. It also indicates that the tagging and marking operations are only allowed in the @TwilightAtomic method.
 *
 * This implies the the @TwilightConsistent method shouldn't have ANY calls to the twilight API we provide to the user
 * (i.e. the twilight API is basically the explicit operations given above). However, this doesn't matter too much since
 * there is no serious problem if the user calls them. Even calling the tagging operations newTag and markField inside
 * the twilight zone has no serious problematic effect. The programmer is just wasting processor time.
 *
 * [2012-04-12 0:33 - UPDATE: many of the comments above that involve TwilightConsistent and TwilightInconsistent
 * annotations are not necessarily out of date. They're very much still true but obviously should be read
 * remembering that they assume I were taking the approach of three separately annotated methods rather than
 * one method with everything in it (transactional zone and twilight zone).]
 *
 * @author Stephen Tuttlebee
 */
public class Twilight {
	// ------------------------------------------------------------------------
	// (SOME) TWILIGHT WORKFLOW OPERATIONS
	// ------------------------------------------------------------------------
	/**
	 * <p>...</p>
	 *
	 * <p>This method has been added to the API the programmer can access because I made
	 * the decision to have everything in a single method rather than spread across three
	 * annotated methods.</p>
	 */
	public static boolean prepareCommit() {
		return false; //TODO
	}

	/**
	 * <p>Restarts the transaction.</p>
	 *
	 * <p>This operation may be called from within the <code>@TwilightAtomic</code>,
	 * <code>@TwilightInconsistent</code>, or <code>@TwilightConsistent</code> methods.
	 * (Although calling from within <code>@TwilightConsistent</code> seems to make little
	 * sense!).</p>
	 */
	public static void restart() {
		// we're going to let the user be able to restart via this operation as the method of choice for restarting a transaction (rather than throwing a TransactionException manually)
	}

	// ------------------------------------------------------------------------
	// TWILIGHT REPAIR OPERATIONS (only allowed in (inconsistent) twilight zone)
	// ------------------------------------------------------------------------
	/**
	 * <p>Reloads a consistent readset.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 */
	public static void reload() {

	}

	/**
	 * <p>Cause inconsistencies/updates by other transactions to be ignored when finalizing the commit.
	 * TODO: explain better</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 */
	public static void ignoreUpdates() {

	}

	/**
	 * <p>Indicates whether the set of fields tagged with tagID contains at least one inconsistent
	 * field.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 *
	 * @param tagID A tag ID
	 * @return <code>true</code> if set of fields tagged with tagID contains at least one inconsistent field,
	 * <code>false</code> otherwise
	 */
	public static boolean isInconsistent(int tagID) {
		return true; // TODO
	}

	/**
	 * <p>Indicates whether the set of fields tagged with tagID contains at least one inconsistent
	 * field, but no other tagged sets contain inconsistencies.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 *
	 * @param tagID A tag ID
	 * @return TODO: semantics of return value are not clear yet...
	 */
	public static boolean isOnlyInconsistent(int tagID) {
		return true; // TODO
	}

	// ------------------------------------------------------------------------
	// TWILIGHT TAG/REGION OPERATIONS (only allowed in transactional zone)
	// ------------------------------------------------------------------------
	// Twilight Tag/Region Operations (tags can be used to mark readset entries and group them according to their semantic meaning)

	/**
	 * <p>Returns a unique integer identifier which represents a new tag, and can be passed as a
	 * parameter to the markField(), isInconsistent(), and isOnlyInconsistent() methods.</p>
	 *
	 * <p>This is a Twilight tag/region operation. It should only be called from within
	 * <code>@TwilightAtomic</code> methods.</p>
	 *
	 * @return Unique integer identifier which represents a new tag
	 */
	public static int newTag() {
		return 0; // TODO
	}

	/**
	 * <p>Given a field from the readset, we record the association of that field with the given tag.
	 * This can be used later by the repair operations isInconsistent() and isOnlyInconsistent().</p>
	 *
	 * <p>This is a Twilight tag/region operation. It should only be called from within
	 * <code>@TwilightAtomic</code> methods.</p>
	 *
	 * @param tagID A tag ID
	 * @param field
	 */
	public static void markField(int tagID, String fieldName, Class<?> owningClass) { // FIXME: problem! We can't have the user using ReadFieldAccess objects!
		// TODO: use reflection to turn fieldName String into Field or whatever and hopefully eventually into
		// a ReadFieldAccess, which I can pass into the call to the markField(int, ReadFieldAccess) method in the Context

		// throw a subclass of RuntimeException if the field does exist for the given class
	}
}
