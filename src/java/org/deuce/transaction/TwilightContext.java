package org.deuce.transaction;

import org.deuce.objectweb.asm.Type;
import org.deuce.transaction.tl2twilight.field.ReadFieldAccess;

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
 * @author stephen
 */
public interface TwilightContext extends org.deuce.transaction.Context {
	// Constants used in dynamically generated code
	final static public Type TWILIGHTCONTEXT_TYPE = Type.getType(TwilightContext.class);
	final static public String TWILIGHTCONTEXT_INTERNAL = Type.getInternalName(TwilightContext.class);
	final static public String TWILIGHTCONTEXT_DESC = Type.getDescriptor(TwilightContext.class);

	// Twilight Workflow Operations
	boolean prepareCommit();
	void finalizeCommit();
	void restart(); // should this not be in the plain vanilla Context API anyway...??

	// Twilight Repair Operations
	void reload(); // reloads a consistent readset
	void ignoreUpdates(); // cause inconsistencies/updates by other Txs to be ignored when finalizing the commit
	boolean isInconsistent(int tagID); // indicates whether the set of fields tagged with tagID contains at least one inconsistent field
	boolean isOnlyInconsistent(int tagID); // indicates whether the set of fields tagged with tagID contains at least one inconsistent field, but no other tagged sets contain inconsistencies

	// Twilight Tag/Region Operations (tags can be used to mark readset entries and group them according to their semantic meaning)
	int newTag(); // returns a unique integer identifier which represents a new tag, and can be passed as a parameter to the markField(), isInconsistent(), and isOnlyInconsistent() methods
	void markField(int tagID, ReadFieldAccess field); // given a field from the readset, we record the association of that field with the given tag
}
