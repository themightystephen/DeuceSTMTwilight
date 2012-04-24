package org.deuce.transaction.tl2twilight;


import org.deuce.transaction.tl2twilight.field.ReadFieldAccess;
import org.deuce.transaction.tl2twilight.field.WriteFieldAccess;
import org.deuce.transform.commons.Exclude;
import org.deuce.trove.THashSet;
import org.deuce.trove.TIntArrayList;
import org.deuce.trove.TIntProcedure;
import org.deuce.trove.TObjectProcedure;

/**
 * Represents the transaction write set.
 *
 * @author Guy Korland
 * @since 0.7
 */
@Exclude
public class WriteSet{
	final private THashSet<WriteFieldAccess> writeSet = new THashSet<WriteFieldAccess>( 16);
	private byte[] locksMarker;
	private final TIntArrayList reservedSet = new TIntArrayList();
	private final TIntArrayList lockedSet = new TIntArrayList();

	public WriteSet(byte[] locksMarker) {
		this.locksMarker = locksMarker;
	}

	public void clear() {
		writeSet.clear();
	}

	public boolean isEmpty() {
		return writeSet.isEmpty();
	}

// TODO: since the forEach itself isn't guaranteed to go over the elements in the order we desire (although I'm not 100% sure right now -- actually, I'm thinking it might be the case that it does do it in a globally consistent order),
// maybe we should just implement ReserveProcedure and LockProcedure in here directly....but wait a minute...do they apply only the write
// set? Maybe... -- I know that in TL2 at least, read-only transactions do not do any locking or validation on-commit; there's no need to.
	public boolean forEach(TObjectProcedure<WriteFieldAccess> procedure) {
		return writeSet.forEach(procedure);
	}

	public void put(WriteFieldAccess write) {
		// Add to write set
		if(!writeSet.add(write))
			writeSet.replace(write); // at first, I didn't fully understand why that when the write object is already in the write set, we need to replace with exactly the same object... --- HMMM, actually, it appears that subclasses of WriteFieldAccess DO HOLD a 'value' field, which can be changed but do not affect the equals() method (which is reliant only on the reference and field position in its class)
	}

	public WriteFieldAccess contains(ReadFieldAccess read) {
		// Check if it is already included in the write set
		return writeSet.get(read);
	}

	public int size() {
		return writeSet.size();
	}



	// REMEMBER: [I think] if we're unable to reserve one or more fields here then a TransactionException is thrown (it's a RuntimeException)
	// TODO: reserve in GLOBALLY CONSISTENT ORDER (NOTE: the documentation of forEach does say "Applies the procedure to each value in the list in ascending (front to back) order")
	public void reserve() {
		writeSet.forEach(new TObjectProcedure<WriteFieldAccess>() {
			@Override
			public boolean execute(WriteFieldAccess writeField) {
				int hashCode = writeField.hashCode();
//				System.out.println("about to try to reserve a field");
				// if self-locked/reserved already, then no need to add hashCode to the reservedSet (since the clashing field has already reserved/locked our shared versioned-lock)
				if(LockManager.reserve(hashCode, locksMarker)) {
//					System.out.println("reserved a field");
					reservedSet.add(hashCode);
				}
				return true;
			}
		});
	}

	// REMEMBER: [I think] if we're unable to lock one or more fields here then a TransactionException is thrown (it's a RuntimeException)
	// TODO: lock in GLOBALLY CONSISTENT ORDER
	public void lock() {
		// lock everything that's in reservation set, add them to locking set and remove them from the reservation set
		reservedSet.forEach(new TIntProcedure() {
			@Override
			public boolean execute(int hashCode) {
				// if self-locked/reserved already, then no need to add hashCode to the lockedSet (since the clashing field has already reserved/locked our shared versioned-lock)
				if(LockManager.lock(hashCode, locksMarker)) {
					lockedSet.add(hashCode);
				}
				return true;
			}
		});
		reservedSet.resetQuick();
	}

	/**
	 * Publishes transactional variables in this write set to memory.
	 *
	 * @return
	 */
	public boolean publish() {
		return writeSet.forEach(new TObjectProcedure<WriteFieldAccess>() {
			@Override
			public boolean execute(WriteFieldAccess writeField) {
				writeField.put();
				return true;
			}
		});
	}

	/**
	 * Used for failing commits (i.e. after a conflict).
	 *
	 * Unlocks all transactional variables in this write set.
	 * Used when we need to unlock but not publish.
	 *
	 * @return
	 */
	// TODO: unlock in GLOBALLY CONSISTENT ORDER (i.e. in reverse order to the locking) [for unlocking it matters less...in fact, it doesn't matter at all I don't think]
	public void unlock() {
		lockedSet.forEach(new TIntProcedure() {
			@Override
			public boolean execute(int hashcode) {
				LockManager.unlock(hashcode,locksMarker);
				return true;
			}
		});
		lockedSet.resetQuick();
	}

	/**
	 * Used for successful commits.
	 *
	 * Publishes transactional variables in this write set to memory and also unlocks them
	 * so other transactions can access them. [wait, this is what I want it to do but I'm not sure
	 * LockTable.setAndReleaseLock() actually does that...]
	 */
	// TODO: unlock in GLOBALLY CONSISTENT ORDER (i.e. in reverse order to the locking) [for unlocking it matters less...in fact, it doesn't matter at all I don't think]
	public void publishAndUnlock() {
		// publish writeset!
		publish();

		// sample clock
		final int commitTime = Context.clock.incrementAndGet();

		// then unlock writeset (NOTE: different method of LockManager called for case of successful commit)
		lockedSet.forEach(new TIntProcedure() {
			@Override
			public boolean execute(int hashcode) {
				LockManager.setAndReleaseLock(hashcode, commitTime, locksMarker);
				return true;
			}
		});
		lockedSet.resetQuick();
	}
}
