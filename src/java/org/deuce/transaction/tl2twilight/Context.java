package org.deuce.transaction.tl2twilight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.deuce.transaction.AbortTransactionException;
import org.deuce.transaction.TransactionException;
import org.deuce.transaction.TwilightContext;
import org.deuce.transaction.tl2twilight.field.BooleanWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.ByteWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.CharWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.DoubleWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.FloatWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.IntWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.LongWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.ObjectWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.ReadFieldAccess;
import org.deuce.transaction.tl2twilight.field.ShortWriteFieldAccess;
import org.deuce.transaction.tl2twilight.field.WriteFieldAccess;
import org.deuce.transaction.tl2twilight.pool.Pool;
import org.deuce.transaction.tl2twilight.pool.ResourceFactory;
import org.deuce.transform.commons.Exclude;
import org.deuce.trove.THashSet;
import org.deuce.trove.TObjectProcedure;

/**
 * Twilight implementation based on TL2 algorithm/implementation, as described in the paper
 * 'Actions in the Twilight: Concurrent Irrevocable Transactions and Inconsistency Repair' by Bieniusa.
 *
 * Some of the key differences/additions of Twilight w.r.t. TL2:
 * - there are region markers; to implement these we will need a stack representing the current region marker (this will be a runtime thing, as most/many things are already)
 *
 * In general:
 * - as well as region markers...
 * - read-set has metadata for each variable: timestamp, region marker, and latest value read
 * - write-set additionally records the last value written to each variable
 * - twilight performs write operations lazily. Inside the Tx it only records the new values locally in the write-set. These write operations
 * only get published to the shared memory on completion of the Tx (i.e. upon commit...probably finalise_commit() rather than prepare_commit())
 *
 * General Thoughts
 * - Surely this TL2 implementation performs some locking on the writeset variables at some point.
 *    * remember one of the differences between TL2 and Twilight-TL2 is that Twilight-TL2 has an extra state for the "lockedness" of writeset variables --
 *    Locked, Free, and Reserved (Reserved is the new one).
 *    	- this may make things difficult if I find that the locking only distinguishes between Locked and Free.
 *		- Have a look into the LockProcedure and LockTable classes
 *		- NOPE, not difficult after all because I can freely change the LockProcedure and LockTable classes as I please. They are used only by the
 *		Context class in this package and so I can change them in whatever way is required (there is callback-style thing going on with the
 *		LockProcedure and LockTable classes from the core Deuce runtime; the only callbacks exist in this Context class. This is the only place
 *		where they are used).
 *
 * The standard TL2 implementation assigned locks Per-Object/Per-Field??? (i.e. not word-based).
 * So therefore, our Twilight-TL2 implementation will do the same.
 *
 * Makes use of Trove library, which is an alternative to the Java collections library as an efficient implementation.
 *
 * Some interesting issues when USING Deuce: http://groups.google.com/group/deuce-stm/browse_thread/thread/5f53ca0a2e0cea7a
 *
 * TODO: consider implementing nested transactions. Or at least, think about how I would do it. Technically, the algorithm specified in the
 * thesis says that TwilightSTM does not naturally work well with nested transactions. (as an aside, I think that is a big drawback of
 * TwilightSTM). However, the JTwilight implementation does experiment with nested transactions. It uses a Stack of Transaction objects
 * to represent the transaction nesting hierarchy a thread has at a particular point in time. If I were to implement it, I would also
 * certainly consider a similar approach involving a stack.
 *
 * @author	Stephen Tuttlebee, derived from TL2 implementation by Guy Korland
 * @since	1.0
 */
@Exclude
final public class Context implements TwilightContext {
	// SHARED/GLOBAL FIELDS (note that they are static)
	/**
	 * Twilight STM has a global counter/timer/clock, just as TL2 does [TL2].
	 */
	public static final AtomicInteger clock = new AtomicInteger(0); // global version-clock (NOTE that clock is publically accessible -- it is incremented in LockProcedure upon unlocking...)
	/**
	 * Global lock used for irrevocable transactions, allowing only one irrevocable transaction to run at a time [TL2].
	 */
	private static final ReentrantReadWriteLock irrevocableAccessLock = new ReentrantReadWriteLock();

	// THREAD-LOCAL FIELDS
	/**
	 * Used by the thread to mark the versioned-locks it holds ?????? [TL2]. ???????
	 * TODO: actually, I'm realising I'm not so sure what this array is about. For some reason, it's 8 times as small as the main lock table (plus 1).
	 */
	final private byte[] locksMarker = new byte[LockManager.LOCK_TABLE_SIZE /8 + 1];

	/**
	 * Read and Write sets [TL2].
	 */
	private ReadSet readSet = new ReadSet();
	final private WriteSet writeSet = new WriteSet(locksMarker);

	/**
	 * Stores the ReadFieldAccess which is set in beforeReadAccess() and later read in onReadAccess() [TL2].
	 * It is used in the pre- and post-validation actions that take place in each of those event handlers, respectively.
	 */
	private ReadFieldAccess currentReadFieldAccess;

	/**
	 * preValidationReadVersionedLock is marked on beforeRead, and used along with startTime for the double lock check. [double lock check refers to the pre- and post-validation that is done on reads]
	 * startTime corresponds to T_init, the value of the global clock/timer when the transaction started.
	 * preValidationReadVersionedLock is the value of the versioned-lock of the current transactional read operation taking place
	 * (used in beforeReadAccess and onReadAccess).
	 */
	private int startTime; // aka 'read-version' described in TL2 paper
	private int preValidationReadVersionedLock;

	/**
	 * Indicates whether this transaction is irrevocable or not [TL2].
	 */
	private boolean irrevocableState = false; // Do NOT put the initialisation of this field anywhere else but here (or the constructor); do not put it in the init() method

	/**
	 * Represents the state of the readset of a transaction [unique to Twilight-TL2]. A readset's state is NOTCHECKED
	 * if no validation has been performed on that readset yet. After validation has been performed the readset's state
	 * is either CONSISTENT or INCONSISTENT. Essentially indicates whether the transaction is consistent w.r.t. the
	 * point in time when the transaction entered the twilight zone (i.e. after prepareCommit()).
	 */
	private enum ReadSetState { NOTCHECKED, CONSISTENT, INCONSISTENT };
	private ReadSetState state;

	/**
	 * Indicates whether transaction is currently in the Twilight Zone or not [unique to Twilight-TL2].
	 */
	private boolean inTwilightZone;

	/**
	 * Tags. Several fields may be marked by the same tag and later inconsistencies can be tracked down in the Twilight
	 * Zone by tag rather than by field. The tagCounter is used to generate new unique integer identifiers for tags.
	 * fieldToTag is used to map individual fields to all the tags they're associated with.
	 */
//	private List<Set<Long>> tagFields; // could get away with using just a list (size of list gives the next tag value to generate) rather than a counter plus a map -- more efficient than using a Map
//	private int tagCounter;
//	private Map<Integer,Set<Long>> tagToFields; // TODO: check whether the representation of fields to Long is right.
//	private Map<Integer,Set<ReadFieldAccess>> tagToFields;
	// TODO: consider the second last post on http://groups.google.com/group/deuce-stm/browse_thread/thread/5f53ca0a2e0cea7a; should I be using the Trove implementation types instead of Map and Set for the static types?
	private List<Set<ReadFieldAccess>> tagFields;

	// EXCEPTIONS
	private static final TransactionException READ_IN_TWILIGHT_EXCEPTION = new TransactionException( "Ordinary reads not allowed in Twilight code. Use *reread* operation instead.");
	private static final TransactionException WRITE_IN_TWILIGHT_EXCEPTION = new TransactionException( "Ordinary writes not allowed in Twilight code. Use *update* operation instead.");
	private static final TransactionException TAG_ALREADY_CONSISTENT_EXCEPTION = new TransactionException( "isOnlyInconsistent(tag) was invoked when the given tag is already consistent. Only invoke isOnlyInconsistent(tag) when the given tag is already known to be inconsistent.");

	//The atomicBlockId argument allows the transaction to log information about the specific atomic block (statically assigned in the bytecode).
	@Override
	public void init(int atomicBlockId, String metainf){
		// (re)initialise transaction state
		this.readSet.clear();
		this.writeSet.clear();
		this.currentReadFieldAccess = null;
		this.state = ReadSetState.NOTCHECKED;
		this.inTwilightZone = false;
//		this.tagCounter = 0;
//		this.tagToFields = new THashMap<Integer,Set<Long>>();
		this.tagFields = new ArrayList<Set<ReadFieldAccess>>(); // TODO: try using a Trove class rather than java.util.ArrayList

		this.objectPool.clear();
		this.booleanPool.clear();
		this.bytePool.clear();
		this.charPool.clear();
		this.shortPool.clear();
		this.intPool.clear();
		this.longPool.clear();
		this.floatPool.clear();
		this.doublePool.clear();

		// lock according to the transaction irrevocable state (NOTE: irrevocableState must only be initialised in the constructor, not here -- due to the fact that as soon as we spot that the transaction needs to become irrevocable (by a call to onIrrevocableAccess()), the transaction is restarted with irrevocableState set to true -- resetting it to false in this init() method would stop it working)
		if(irrevocableState)
			irrevocableAccessLock.writeLock().lock();
		else
			irrevocableAccessLock.readLock().lock();

		// sample the global clock to get start time (T_init) (NOTE: sampling the clock MUST occur after (the potential) locking of the irrevocableAccessLock; see message of first commit appearing on Feb 10th 2011)
		this.startTime = clock.get();
	}

	/* ***************************************
	 * ENTERING AND EXITING THE TWILIGHT ZONE
	 * ***************************************/
	/**
	 * <p>Twilight-specific method/operation. First phase of commit before twilight zone.</p>
	 *
	 * <p>This method actually does a lot of stuff in common with the standard TL2; the
	 * bit done in this method is to first reserve the writeset and then to validate
	 * the readset. The returned boolean value indicates whether the readset was consistent
	 * or not.</p>
	 *
	 * <p>TODO: maybe remove this paragraph if I determine the optimisation is wrong.
	 * <br />However, as an optimisation, if the write set is empty, <code>true</code> is
	 * returned (even if the read set is not consistent, since it does not matter whether
	 * the read set is consistent or not when the write set is empty).</p>
	 *
	 * <p>Remember that with Twilight, even if the validation of the readset fails, there
	 * is still opportunity for the programmer to repair inconsistencies inside the
	 * twilight zone. Therefore we don't restart in the presence of inconsistencies.
	 * Instead, we continue into the twilight zone where the programmer can choose to
	 * bail out or otherwise (and if we find at the end of the finalize commit phase that
	 * the readset inconsistencies have not been resolved, then we we restart the Tx).</p>
	 *
	 * @return <code>true</code> if the readset is consistent, <code>false</code> otherwise
	 */
	@Override
	public boolean prepareCommit() {
		System.out.println(Thread.currentThread()+" Inside prepareCommit() using Context object "+this);
		// NOTE: the optimisation below is not seen in the twilight algorithm...but then again, it's an implementation detail and so you might not expect to; for now we leave it out
//		// optimisation for read-only transactions (no need to reserve (transactional) variables in the writeset because it's empty. Also no need to validate readset).
//		if (writeSet.isEmpty()) {
//			return true;
//		}
//	    else {

			// reserve the transactional variables in the write set (can throw TransactionException if some fields in write set are LOCKED -- TODO: check this)
			System.out.println(Thread.currentThread()+" About to try reserving write set");
			// exception can occur if not able to reserve all the write set locks
			writeSet.reserve(); // if reservation fails, then transaction is automatically restarted (by relying on exception mechanism in instrumented atomic method to call context.rollback())
			System.out.println(Thread.currentThread()+" Succeeded in reserving write set");
			// validate the transactional variables in the read set
			return validate();
//	    }
	}

	/**
	 * @return <code>true</code> if the readset is consistent, <code>false</code> otherwise
	 */
	@Override
	public boolean finalizeCommit() {
		System.out.println(Thread.currentThread()+" Inside finalizeCommit() using Context object "+this);
		try {
			// return false if read set still inconsistent even after Twilight zone
			if(state != ReadSetState.CONSISTENT) {
				System.out.println(Thread.currentThread()+" Readset found to be inconsistent at the start of finalizeCommit");
				return false;
			}
			// otherwise, read set was consistent and we can publish write set (performing necessary locking and unlocking to do so)
			System.out.println(Thread.currentThread()+" About to try locking write set");
			writeSet.lock();
			System.out.println(Thread.currentThread()+" Succeeded in locking write set");
			writeSet.publishAndUnlock();
			System.out.println(Thread.currentThread()+" Succeeded in publishing and unlocking write set");
			System.out.println("SUCCESSFUL COMMIT");
			return true;
		}
		// unreserve writeset if readset is inconsistent...
		// also return false if locking of the writeset fails for some STRANGE reason
		// (I don't think writeSet.lock() can fail since we reserved writeset already and so we should be able to lock unhindered by other transactions... -- one transaction cannot reserve (and afterwards lock) a field that has already been reserved)
		catch(TransactionException ex) {
			writeSet.unlock();
			return false;
		}
		finally {
			// set irrevocable state appropriately and also release appropriate locks (this is always run before returning)
			if(irrevocableState) {
				irrevocableState = false;
				irrevocableAccessLock.writeLock().unlock();
			}
			else {
				irrevocableAccessLock.readLock().unlock();
			}
		}
	}

	/**
	 * @return boolean <code>true</code> if commit was successful (readset was consistent and writeset was successfully
	 * reserved and locked and written to memory), <code>false</code> otherwise. In the latter case, the instrumented
	 * atomic method's retry loop will kick into action, causing a restart of the transaction.
	 */
	@Override
	public boolean commit() {
		System.out.println(Thread.currentThread()+" Start of commit()");
		try {
			prepareCommit();
			return finalizeCommit(); // returns false if readset inconsistencies
		}
		// failed to reserve writeset during prepareCommit() (returning false will cause the atomic method's retry loop to iterate again)
		catch(TransactionException ex) {
			writeSet.unlock();
			return false;
		}
	}

	@Override
	public void rollback() {
		// unreserve/unlock writeset entries so other transactions can read/write fields
		writeSet.unlock();
		// release irrevocableAccessLock read lock (read lock since any transaction holding the write lock WILL NEVER rollback)
		irrevocableAccessLock.readLock().unlock();

		// lazy version management means that no undo is necessary
		// write set contents are simply cleared on next call to Context.init()
		// (same for read set)
	}

	/**
	 * <p>Method to allow the programmer to restart the transaction.</p>
	 *
	 * <p>NOTE: I believe it is allowable for the programmer to restart from either within the transactional part of the
	 * transaction or the twilight zone. I know for certain it is allowable within the twilight zone (the examples
	 * in Bienuisa's thesis do so). I would have thought it is fine within the transactional code as well
	 * but there are no concrete examples where this is done. --- UPDATE: well, I know its okay ordinarily with TL2
	 * and any other algorithm because you can just throw a TransactionException inside an @Atomic method's body
	 * to cause a retry/restart.</p>
	 */
	@Override
	public void restart() {
//		// NOTE: Only allow calls to restart() from within the Twilight code, otherwise it's a runtime exception (would be nice if we could statically check this)
//		if(inTwilightZone) {
			// perform any unlocking of write set entries that may be necessary
			if(state != ReadSetState.NOTCHECKED) {
				writeSet.unlock();
			}
			// throw exception to cause control flow to retry/restart the transaction
			throw new TransactionException("Explicit Transaction Restart."); // TODO: maybe 'throw TransactionException.STATIC_TRANSACTION;'
//		}
//		else {
//			// programmer misused restart operation, therefore throw exception (note: the exception causes NO retry/restart of the transaction to happen)
//			throw new AbortTransactionException();
//		}
	}

	/* *******************************
	 * INTERNAL OPERATIONS
	 * ******************************/
	/**
	 * <p>Twilight-specific Internal method/operation (only called by prepareCommit()).</p>
	 *
	 * <p>Validates the readset of the current transaction for consistency (or
	 * inconsistency, as the case may be).</p>
	 *
	 * @return <code>true</code> if the readset is consistent, <code>false</code> otherwise
	 */
	private boolean validate() {
		state = ReadSetState.CONSISTENT;

		// check read set has not been invalidated (either due to lock being held by another Tx or its version out-dated) (exception indicates inconsistency)
		try {
			readSet.checkClock(startTime);
		}
		catch(TransactionException e) {
			state = ReadSetState.INCONSISTENT;
//			System.out.println("validating found readset to be inconsistent");
			return false;
		}
		return true;
	}

	// Other internal operations such as lock(), reserve(), publishAndUnlock(), and unlock(), are implemented in LockManager
	// TODO: since all the other internal operations are performed elsewhere (in LockManager), and given that validate() is
	// only called by prepareCommit(), I am tempted to inline the contents of validate() inside prepareCommit().

	/* *******************************
	 * TWILIGHT / REPAIR OPERATIONS
	 * ******************************/
	// NOTE: STMIgnoreInconsistencies()/STMIgnoreUpdates() included here despite the Algorithm chapter not mentioning it (the operation allows the programmer to ignore inconsistencies; finalizeCommit() can go ahead without throwing an exception)
	// NOTE: isInconsistent() and isOnlyInconsistent() also included here -- again, the Algorithm chapter doesn't mention them.
	// reload() operation is given in Algorithm chapter.
	// TODO: update and reread operations need to be handled specially (requiring something similar to the existing onReadAccess and onWriteAccess events -- or maybe I can utilise the existing events and alter the code in those event handlers with a simple if statement...could I not just say "if we're in twilight zone, read like this (i.e. STMReread()), else, read like this (i.e. STMRead())"; similarly for writes).

	/**
	 * Reload a consistent readset.
	 */
	@Override
	public void reload() {
		// if already consistent, nothing to do
		if(state == ReadSetState.CONSISTENT) {
			return;
		}
		// but otherwise
		else {
			boolean snap = false;
			final ReadSet newReadset = new ReadSet();
			// attempt to get consistent snapshot of read set (hence variable name 'snap'); if fail to do so, keep trying
			while(!snap) {
				final int reloadTime = clock.get();
				newReadset.clear(); // TODO: is there a 'quick clear' I could use instead (doesn't seem to exist in THashSet)? (Also, looking at the code of THashSet I think clearing is probably faster than creating a new ReadSet object)
				snap = readSet.forEach(new TObjectProcedure<ReadFieldAccess>() { // stops looping on first false returned [I think]
					@Override
					public boolean execute(ReadFieldAccess readFieldAccess) {
						// use pre- and post-checks to ensure we atomically load transactional variable's value and versioned-lock
						int expected = LockManager.checkLock(readFieldAccess.hashCode(), reloadTime); // pre-check
						newReadset.add(readFieldAccess);
						try {
							LockManager.checkLock(readFieldAccess.hashCode(), reloadTime, expected); // post-check
						}
						catch(TransactionException te) {
							return false; // (effectively like the 'break' in the algorithm given in the thesis)
						}

						return true;
					}
				});
			}
			state = ReadSetState.CONSISTENT;
			readSet = newReadset;
		}
	}

	/**
	 * The programmer can call this if the modifications/updates that have been issued by other transactions
	 * are not semantically invalidating the transaction. The programmer can state that this transaction's
	 * updates are still to be issued and that the inconsistencies are to be ignored.
	 *
	 * NOTE: This is not mentioned in the Algorithm chapter but is in other chapters.
	 */
	@Override
	public void ignoreUpdates() {
		// NOTE: Only allowed calls to ignoreInconsistencies/ignoreUpdates from within Twilight Zone (would be nice if we could statically check this).
		if(inTwilightZone) {
			state = ReadSetState.CONSISTENT;
		}
		else {
			// programmer misused ignoreUpdates operation, therefore throw exception (note: the exception causes NO retry/restart of the transaction to happen)
			throw new AbortTransactionException();
		}
	}

	/**
	 * <p>Indicates whether the set of fields tagged with tagID contains at least one inconsistent
	 * field.</>
	 *
	 * <p>Note:
	 * The implementation of some things might be affected by the fact that we need to the flexibility to
	 * know which particular fields are inconsistent (i.e. what tagging is for). If a field x
	 * and field y are tagged with tag t, then we have to know whether fields x and y have individually
	 * become inconsistent. We can't just look at the coarse-grained level of the entire readset. That's
	 * one of the benefits of TwilightSTM; it has the possibility of being more fine-grained in the sense
	 * that we can ignore certain inconsistencies in the readset if we wish, rather than rollback and
	 * restart the transaction as soon as we find even one field in the readset becoming inconsistent.</p>
	 *
	 * @param tagID Tag identifier to be checked for consistency
	 * @return <code>true</code> if all fields marked with the given tag are consistent, otherwise <code>false</code>
	 */
	@Override
	public boolean isInconsistent(int tagID) {
		// optimisation: if entire readset has already been found to be consistent, then obviously all fields of any given tag will be as well
		if(state != ReadSetState.CONSISTENT) {
			return false;
		}

		final Set<ReadFieldAccess> taggedFields = tagFields.get(tagID);
		// for each field tagged with the tag 'tag', check whether the field is consistent
		boolean inconsistent = readSet.forEach(new TObjectProcedure<ReadFieldAccess>() {
			@Override
			public boolean execute(ReadFieldAccess rfa) {
				// if the field is tagged with the given tag, then check its consistency
				if(taggedFields.contains(rfa)) {
					try {
						LockManager.checkLock(rfa.hashCode(), startTime);
					}
					catch(TransactionException te) {
						// inconsistent
						return true;
					}
					// consistent
					return false;
				}
				// for other fields, we just pretend they are consistent (we are only concerned with consistency of tagged fields)
				return false;
			}
		});

		return inconsistent;
	}

	/**
	 * Indicates whether the set of fields tagged with tagID contains at least one inconsistent field, but
	 * no other tagged sets contain inconsistencies.
	 *
	 * NOTE: this operation requires that it is called when it is already known that the given tag is inconsistent.
	 * The semantics of the operation would not be clear if this precondition was not met.
	 *
	 * @param tagID
	 * @return
	 */
	@Override
	public boolean isOnlyInconsistent(int tagID) {
		boolean tagInconsistent = isInconsistent(tagID);
		if(!tagInconsistent) {
			throw TAG_ALREADY_CONSISTENT_EXCEPTION;
		}

		boolean otherTagsInconsistent = false;

		// check each (other) tag for (in)consistency
		for(int i = 0; i < tagFields.size(); i++) {
			if(i == tagID) continue;
			otherTagsInconsistent = isInconsistent(i);
			// optimisation: break out as soon as we find an inconsistent tag (other than the given tag)
			if(otherTagsInconsistent) break;
		}

		// if given tag is inconsistent and all other tags are consistent, then given tag is indeed the only inconsistent tag
		return (tagInconsistent && !otherTagsInconsistent);
	}

	/* *******************************
	 * TAG / REGION OPERATIONS
	 * ******************************/
	/**
	 * <p>Returns a unique integer identifier which represents a new tag, and can be passed as a
	 * parameter to the markField(), isInconsistent(), and isOnlyInconsistent() methods.</p>
	 *
	 * <p>Note that tags are only valid for the extent of one transaction.</p>
	 */
	@Override
	public int newTag() {
//		tagCounter++;
		// for efficiency, construct empty set in the Map so we don't have to check each time inside addTag() (but remember we can't assume the user will actually use the tag i.e. they might not ever call addTag())
//		tagToFields.put(tagCounter, new THashSet<ReadFieldAccess>());
		tagFields.add(new THashSet<ReadFieldAccess>());

		// the size of the list is used to get the tag identifier
		return tagFields.size() - 1;
	}

	/**
	 * (aka STMMark())
	 * Given a field from the readset, we record the association of that field with the given tag.
	 * Tags can be used to mark readset entries and group them according to their semantic meaning.
	 *
	 * In the Twilight code, the programmer can use the tags to determine which kind of memory
	 * locations was marked as inconsistent and compensate for it as applicable.
	 *
	 * @param tagID
	 * @param field
	 */
	// one field can have multiple tags
	// one tag can be associated with multiple fields
	// therefore each field is mapped to a set of tags
	// the thing is, we need to be able to quickly retrieve the fields associated with a given tag in the isInconsistent() method.
	// so instead, better to map each tag to set of fields instead
	// TODO: tempted to rename from addTag() to mark(), since it is more about marking fields with certain tags (addTag() sounds too similar to newTag())
//	public void markField(int tag, long field) {
//		tagToFields.get(tag).add(field);
//	}
	@Override
	public void markField(int tagID, Object ownerObj, long fieldAddress) {
		// NOTE: we assume that 'field' is in the readset. We can safely assume this if we generate the code that calls this method (i.e. instrumentation).
		// Above comment is out-of-date; I may well. I require the programmer to use Twilight API and provide the name of a field. The API implementation does check if the field belongs to the class but currently it doesn't check if its in the readset...
		// we can't rely on the fact that we're generating code to guarantee correctness because its not pre-determined where the call to markFields happens and even how many times you can do it. The programmer needs the flexibility to call it whenever they like. Thus we are providing an API to do it.
		// TODO: maybe perform check of whether field is in readset here rather than in the caller to this method (i.e. the Twilight class) -- thankfully the equals() method of ReadFieldAccess is defined in such a way that two ReadFieldAccess objects are equal if they refer to the same field (rather than if they are the exact same object)
		// assuming I can do the check to see if the field is in the readset, do I throw an (runtime?) exception if it's not?
		ReadFieldAccess rfa = new ReadFieldAccess(ownerObj, fieldAddress);
		if(readSet.contains(rfa)) {
			tagFields.get(tagID).add(rfa);
		}
		else {
			throw new RuntimeException("Attempt to mark field "+rfa+" which is not "); // FIXME: would be nice to not throw RuntimeExceptions every time the programmer breaks the rules. It means the programmer can't be sure their program won't catastrophically fail in certain situations where a certain branch is taken which normally isn't taken (and therefore is not noticed in basic tests -- really it depends how much testing the programmer does).
			// FIXME: give better error message; show precisely which field it was... -- solve by catching this specific exception in Twilight.markField() and rethrowing it with more detail about the specific field the programmer tried to mark (we have the details about the field in the caller context but not the callee context (i.e. here)) -- I may remove the String message from the exception thrown here and only put it in the caller's rethrow of the exception
		}
	}

	/* *******************************
	 * TRANSACTIONAL READ/WRITE OPERATIONS
	 * ******************************/
	/**
	 * <p>"The reason for having two events in the getter is technical: the 'before' and 'after' events
	 * allow the STM backend to verify that the value of the field, which is accessed between both
	 * events without using costly reflection mechanisms, is consistent. This is typically the case
	 * in time-based STM algorithms like TL2 and LSA that ship with Deuce". [p26-27 of Korland Thesis]
	 * </p>
	 *
	 * <p>The synthetic Getter methods added for each field in the original class by the Deuce agent
	 * first call Context.beforeReadAccess() and then Context.onReadAccess(), one after the other.
	 * (see p26 of Korland thesis). Remember that these synthetic Getter methods will be called from
	 * similarly synthetic methods to provide transactional support for a method which was marked
	 * Atomic by the programmer.</p>
	 *
	 * <p>Pre-validation: check read is valid before actually reading the value of the field.</p>
	 *
	 *
	 *
	 *
	 * @param obj The owning object of the field being read.
	 * @param field The address of the field being read which is stored in the synthetic field field__ADDRESS__ (in either the same class as the field itself, or in a synthetic fields holder class)
	 */
	// 1.
	@Override
	public void beforeReadAccess(Object obj, long field) {
		// ORDINARY reads not allowed within twilight zone (must use 'reread' operation); throw runtime exception to indicate such [specific to Twilight]
		// TODO: check this statically (rather than at runtime).
		if(inTwilightZone) {
			throw READ_IN_TWILIGHT_EXCEPTION;
		}

		// construct new ReadFieldAccess and store temporarily in currentReadFieldAccess
		ReadFieldAccess readEntry = new ReadFieldAccess(obj, field);
		currentReadFieldAccess = readEntry;

		/*
		 * NOTE: Having this println statement uncommented sometimes has some STRANGE (bad) effects. Seems to occur when the field being accessed
		 * is a reference type (i.e. an object) (although remember that objects declared as final are not tracked by the STM system and so would
		 * not be affected by this. Seems to be due to printing out 'obj' -- get a NullPointerException (if you make sure you catch the exception
		 * inside the @Atomic method).
		 *
		 * REASON: because the object passed is a VERY SPECIAL kind of object that is returned from a method of the sun.misc.Unsafe class -- the
		 * documentation for it says you're not meant to use it as a normal object but only to use it later as input to a different method of
		 * sun.misc.Unsafe. See http://www.docjar.com/docs/api/sun/misc/Unsafe.html#staticFieldBase(Field).
		 */
		//System.out.println("object: "+obj);

		// Check the read is valid (PRE-VALIDATION step described in the thesis on p32)
		preValidationReadVersionedLock = LockManager.checkLock(currentReadFieldAccess.hashCode(), startTime);
		System.out.println(Thread.currentThread()+" No exception happened during the checkLock in beforeReadAccess");
		// Pre-validation passed, so add to read set (if post-validation subsequently fails, not a problem since transaction will just abort and restart)
		readSet.add(currentReadFieldAccess);
	}

	/**
	 * <p>Post-validation: check read is valid AFTER actually reading the value of the field. If
	 * it is still valid, then we know that the value we read was consistent.</p>
	 *
	 * <p>This is the underlying method that is called by all the onReadAccess(...) methods
	 * below.</p>
	 */
	// 2.
	private WriteFieldAccess onReadAccess(Object obj, long field){
		// Check the read is still valid (same lock state and version as in pre-validation) (POST-VALIDATION step described in the thesis on p32)
		LockManager.checkLock(currentReadFieldAccess.hashCode(), startTime, preValidationReadVersionedLock);
		System.out.println(Thread.currentThread()+" No exception happened during the checkLock in onReadAccess");

		// Check if it is already included in the write set (returns null if not in write set) (NOTE: contains method uses equals() method of one of the objects to do this - ReadFieldAcess and WriteFieldAccess objects share common type (ReadFieldAccess) and also common equals and hashcode methods)
		return writeSet.contains(currentReadFieldAccess);
	}

	private void onWriteAccess( WriteFieldAccess write){
		// ORDINARY writes not allowed within twilight zone (must use 'update' operation); throw runtime exception to indicate such [specific to Twilight]
		// TODO: check this statically (rather than at runtime).
		if(inTwilightZone) {
			throw WRITE_IN_TWILIGHT_EXCEPTION;
		}

		// Add to write set
		writeSet.put(write);
	}


	// in each of these onReadAccess methods below, value is the result of the bytecode performing a direct memory read before calling them; if validation succeeds we just return the value they provided. If this transactions has written to it already, then return the value written. If validation fails (another Tx has written to it), then we abort
	// 2.
	@Override
	public Object onReadAccess(Object obj, Object value, long field){
		// perform post-validation on field being read (to ensure read of value is consistent) and
		// then check whether field being read is already in write set
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if(writeAccess == null) {
			// not in write set, return value read (which we know is consistent)
			return value;
		}
		// is in write set, return value for this field stored in write set
		return ((ObjectWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public boolean onReadAccess(Object obj, boolean value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((BooleanWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public byte onReadAccess(Object obj, byte value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((ByteWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public char onReadAccess(Object obj, char value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((CharWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public short onReadAccess(Object obj, short value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((ShortWriteFieldAccess)writeAccess).getValue();

	}

	@Override
	public int onReadAccess(Object obj, int value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((IntWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public long onReadAccess(Object obj, long value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((LongWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public float onReadAccess(Object obj, float value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((FloatWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public double onReadAccess(Object obj, double value, long field) {
		WriteFieldAccess writeAccess = onReadAccess(obj, field);
		if( writeAccess == null)
			return value;

		return ((DoubleWriteFieldAccess)writeAccess).getValue();
	}

	@Override
	public void onWriteAccess( Object obj, Object value, long field){
		ObjectWriteFieldAccess next = objectPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, boolean value, long field) {

		BooleanWriteFieldAccess next = booleanPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, byte value, long field) {

		ByteWriteFieldAccess next = bytePool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, char value, long field) {

		CharWriteFieldAccess next = charPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, short value, long field) {

		ShortWriteFieldAccess next = shortPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, int value, long field) {

		IntWriteFieldAccess next = intPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, long value, long field) {

		LongWriteFieldAccess next = longPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, float value, long field) {

		FloatWriteFieldAccess next = floatPool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	@Override
	public void onWriteAccess(Object obj, double value, long field) {

		DoubleWriteFieldAccess next = doublePool.getNext();
		next.set(value, obj, field);
		onWriteAccess(next);
	}

	// Remember that this callback method is called before entering an irrevocable block. User adds the @Irrevocable annotation to indicate such a block.
	@Override
	public void onIrrevocableAccess() {
		if(irrevocableState) // already in irrevocable state so no need to restart transaction.
			return;

		irrevocableState = true;
		throw TransactionException.STATIC_TRANSACTION;
	}

	private static class ObjectResourceFactory implements ResourceFactory<ObjectWriteFieldAccess>{
		@Override
		public ObjectWriteFieldAccess newInstance() {
			return new ObjectWriteFieldAccess();
		}
	}
	final private Pool<ObjectWriteFieldAccess> objectPool = new Pool<ObjectWriteFieldAccess>(new ObjectResourceFactory());

	private static class BooleanResourceFactory implements ResourceFactory<BooleanWriteFieldAccess>{
		@Override
		public BooleanWriteFieldAccess newInstance() {
			return new BooleanWriteFieldAccess();
		}
	}
	final private Pool<BooleanWriteFieldAccess> booleanPool = new Pool<BooleanWriteFieldAccess>(new BooleanResourceFactory());

	private static class ByteResourceFactory implements ResourceFactory<ByteWriteFieldAccess>{
		@Override
		public ByteWriteFieldAccess newInstance() {
			return new ByteWriteFieldAccess();
		}
	}
	final private Pool<ByteWriteFieldAccess> bytePool = new Pool<ByteWriteFieldAccess>( new ByteResourceFactory());

	private static class CharResourceFactory implements ResourceFactory<CharWriteFieldAccess>{
		@Override
		public CharWriteFieldAccess newInstance() {
			return new CharWriteFieldAccess();
		}
	}
	final private Pool<CharWriteFieldAccess> charPool = new Pool<CharWriteFieldAccess>(new CharResourceFactory());

	private static class ShortResourceFactory implements ResourceFactory<ShortWriteFieldAccess>{
		@Override
		public ShortWriteFieldAccess newInstance() {
			return new ShortWriteFieldAccess();
		}
	}
	final private Pool<ShortWriteFieldAccess> shortPool = new Pool<ShortWriteFieldAccess>( new ShortResourceFactory());

	private static class IntResourceFactory implements ResourceFactory<IntWriteFieldAccess>{
		@Override
		public IntWriteFieldAccess newInstance() {
			return new IntWriteFieldAccess();
		}
	}
	final private Pool<IntWriteFieldAccess> intPool = new Pool<IntWriteFieldAccess>( new IntResourceFactory());

	private static class LongResourceFactory implements ResourceFactory<LongWriteFieldAccess>{
		@Override
		public LongWriteFieldAccess newInstance() {
			return new LongWriteFieldAccess();
		}
	}
	final private Pool<LongWriteFieldAccess> longPool = new Pool<LongWriteFieldAccess>( new LongResourceFactory());

	private static class FloatResourceFactory implements ResourceFactory<FloatWriteFieldAccess>{
		@Override
		public FloatWriteFieldAccess newInstance() {
			return new FloatWriteFieldAccess();
		}
	}
	final private Pool<FloatWriteFieldAccess> floatPool = new Pool<FloatWriteFieldAccess>( new FloatResourceFactory());

	private static class DoubleResourceFactory implements ResourceFactory<DoubleWriteFieldAccess>{
		@Override
		public DoubleWriteFieldAccess newInstance() {
			return new DoubleWriteFieldAccess();
		}
	}
	final private Pool<DoubleWriteFieldAccess> doublePool = new Pool<DoubleWriteFieldAccess>( new DoubleResourceFactory());

}
