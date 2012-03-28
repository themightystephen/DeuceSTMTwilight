package org.deuce.transaction.tl2twilight;

import org.deuce.transaction.tl2twilight.field.ReadFieldAccess;
import org.deuce.transaction.tl2twilight.field.WriteFieldAccess;
import org.deuce.transform.Exclude;
import org.deuce.trove.THashSet;
import org.deuce.trove.TObjectProcedure;

/**
 * Represents the transaction read set.
 * And acts as a recycle pool of the {@link ReadFieldAccess}.
 *
 * Note that readset does NOT store the values being read, only which fields of which objects have been read.
 *
 * @author Guy Korland
 * @author Stephen Tuttlebee
 * @since 0.7
 */
@Exclude
public class ReadSet {
	private static final int DEFAULT_CAPACITY = 1024;
//	private ReadFieldAccess[] readSet = new ReadFieldAccess[DEFAULT_CAPACITY]; // TODO: consider whether there is a good reason to use built-in Java arrays rather than THashSet to implement our ReadSet. Why did the original author (Guy Korland) choose Java arrays in ReadSet and THashSet in WriteSet. Could it be that THashSet does not handle the larger (default) capacity of 1024 as effiently as built-in Java arrays?
	final private THashSet<ReadFieldAccess> readSet; // we use Trove library class rather than Java arrays (maybe we lose a little efficiency but it's needed in order to have a forEach method in this class)

	public ReadSet() {
		readSet = new THashSet<ReadFieldAccess>(DEFAULT_CAPACITY);
	}

	public void add(ReadFieldAccess rfa) {
		readSet.add(rfa);
	}

	public void clear() {
		readSet.clear();
	}

	// for EVERY variable in the read set, we check that its associated lock........ -- a variable's index into the lock table is given by the hashcode of its current value
	// part of VALIDATION process for read set (LockTable.checkLock in the call chain is also part of the process).
	// For each variable in the read set, validate that their associated versioned-lock is still valid
    public void checkClock(final int clock) {
    	readSet.forEach(new TObjectProcedure<ReadFieldAccess>() {
			@Override
			public boolean execute(ReadFieldAccess rfa) {
				LockManager.checkLock(rfa.hashCode(), clock);
	        	rfa.clear();
	        	return true;
			}
		});
    }

    /**
     * <p>Perform the given action for each transactional variable in this readset.</p>
     *
     * <p>This method gives the flexibility to perform an operation outside of this class
     * on every element of this readset.</p>
     *
     * @param procedure Procedure to be executed on every transactional variable in this readset
     * @return
     */
	public boolean forEach(TObjectProcedure<ReadFieldAccess> procedure) {
		return readSet.forEach(procedure);
	}

    /**
     * TODO: Work out what this interface is for / where it is used
     * Initial lookup of this method yields no results -- can't find a use of it anywhere.
     * Consider removing.
     */
    public interface ReadSetListener {
    	void execute(ReadFieldAccess read);
    }
}