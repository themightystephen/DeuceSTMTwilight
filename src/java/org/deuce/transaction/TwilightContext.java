package org.deuce.transaction;

import org.deuce.objectweb.asm.Type;
import org.deuce.transform.commons.Exclude;

/**
 * TwilightContext extends the standard STM Context interface provided by Deuce.
 * This interface should be implemented when support for Twilight operations
 * is desired in an implementation.
 *
 * The Twilight API itself is still compatible with the standard STM API. One should
 * still be able to use a Twilight implementation like a standard STM, without ever
 * having to use its twilight operations.
 *
 * Standard STM operations such as commit() can still be implemented in Twilight
 * STM, by simply implementing it in terms of prepareCommit() and finalizeCommit().
 *
 * NOTES RELATING TO PROGRAMMER INTERFACE AND CLASS TRANSFORMATION
 * The isInconsistent(tag) and isOnlyInconsistent(tag) operations should still be
 * callable by the programmer. They are NOT redundant because they check inconsistency
 * based on a particular tag rather than the entire readset. The consistency check
 * for the entire readset is done by the prepareCommit() operation. In the generated
 * code, we would do call the prepareCommit() method and use its return value to
 * determine whether to call the @TwilightConsistent or @TwilightInconsistent
 * method [but what if there isn't one or both of these...we need to have a default
 * action......].
 *
 * PROVIDING A USER API (Twilight class)
 * ====================
 * Explicit operations below are the ones which are provided in the user API. The implicit ones are not.
 *
 * Implicit operations (location of call is pre-determined and are performed during method transformations)
 * ===================
 * [init] (transactional zone)
 * prepareCommit (transactional zone)
 * finalizeCommit (twilight zone; both consistent and inconsistent)
 *
 * Explicit operations (location chosen by the programmer; with some limitations]
 * ===================
 * restart (either; inconsistent)
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
 * @author Stephen Tuttlebee
 */
@Exclude
public interface TwilightContext extends org.deuce.transaction.Context {
	// Constants used in dynamically generated code
	final static public Type TWILIGHTCONTEXT_TYPE = Type.getType(TwilightContext.class);
	final static public String TWILIGHTCONTEXT_INTERNAL = Type.getInternalName(TwilightContext.class);
	final static public String TWILIGHTCONTEXT_DESC = Type.getDescriptor(TwilightContext.class);

	// Twilight Workflow Operations
	boolean prepareCommit();
	boolean finalizeCommit();
	void restart(); // TODO: should this not be in the plain vanilla Context API anyway...??
	// restart is definitely allowed in the twilight zone (see p28 of Bienuisa's thesis); don't know yet if allowed in main body of Tx

	// Twilight Repair Operations (only allowed in twilight zone)
	void reload(); // reloads a consistent readset
	void ignoreUpdates(); // cause inconsistencies/updates by other Txs to be ignored when finalizing the commit
	boolean isInconsistent(int tagID); // indicates whether the set of fields tagged with tagID contains at least one inconsistent field
	boolean isOnlyInconsistent(int tagID); // indicates whether the set of fields tagged with tagID contains at least one inconsistent field, but no other tagged sets contain inconsistencies

	// Twilight Tag/Region Operations (tags can be used to mark readset entries and group them according to their semantic meaning)
	int newTag(); // returns a unique integer identifier which represents a new tag, and can be passed as a parameter to the markField(), isInconsistent(), and isOnlyInconsistent() methods
//	void markField(int tagID, ReadFieldAccess field); // given a field from the readset, we record the association of that field with the given tag
	void markField(int tagID, Object owningObj, long fieldAddress); // given a field from the readset, we record the association of that field with the given tag
	// actually, the field is given 'indirectly' by providing the owning object of the field (what happens if it's static field?) and the address of the field as stored in the object's synthetic XXX__ADDRESS__ field (but that field might be stored in one of two places...either in the main class itself (i.e. the same class as the field belongs to) or in a separate holder class (e.g. XXXDeuceFieldsHolder)
}
