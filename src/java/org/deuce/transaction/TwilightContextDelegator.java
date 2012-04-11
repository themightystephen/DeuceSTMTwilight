package org.deuce.transaction;

import org.deuce.objectweb.asm.Type;

/**
 * Twilight Workflow Operations (init, prepareCommit, finalizeCommit, commit, rollback), similar to ordinary STM
 * workflow operations (init, commit, rollback) are NOT delegated by this class. They are called on directly in
 * the generated code rather than via this delegator.
 *
 * Twilight Repair Operations (reload, ignoreUpdates, isInconsistent, and isOnlyInconsistent) which should only
 * appear within the Twilight zone will be called directly on the Context *by the programmer* from within their
 * original (untransformed) code, since they are inherently operations that are the choice of the programmer.
 * They are not implicit operations such as init, commit, or rollback, which are always called regardless, and in
 * fixed places (e.g. often the beginning or end).
 *
 * @author stephen
 *
 */
public class TwilightContextDelegator extends ContextDelegator {
	//DEFAULT_TWILIGHTCONTEXT_CLASS??
	final static public Class<? extends Context> DEFAULT_CONTEXT_CLASS = org.deuce.transaction.tl2twilight.Context.class;

	//CONTEXT_DELEGATOR_INTERNAL????
	final static public String TWILIGHTCONTEXT_DELEGATOR_INTERNAL = Type.getInternalName(TwilightContextDelegator.class);


	// these methods ........
}
