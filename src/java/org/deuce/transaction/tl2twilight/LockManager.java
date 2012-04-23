package org.deuce.transaction.tl2twilight;

import java.util.concurrent.atomic.AtomicIntegerArray;

import org.deuce.transaction.TransactionException;
import org.deuce.transform.commons.Exclude;

// this represents the striped locking that takes place on all the elements of memory...
// here we have an AtomicIntegerArray of locks -- there are 2^20 =~ 1 million locks (each 32-bit lock words)

// In Twilight-TL2, a lock can be either Free, Reserved, or Locked. The extra state, Reserved, dictates that some
// change is needed from the original TL2 code


// The way this locking business works can probably be modelled with an automaton:
// State 'Free' goes to 'Reserved' when we hit prepareCommit(). We then go from the 'Reserved' state to 'Locked' when we hit finalizeCommit() and
// we're wanting to publish the values of the transactional variables in the writeset to memory. After publishing, we unlock them and each
// transactional variable (aka field) goes from the 'Locked' state back to 'Free'. The 'Reserved' state basically allows concurrent reads of the
// reserved field to take place whilst the reserving transaction executes the twilight zone.
// But obviously, if a field is already Reserved, then another thread/transaction which tries to reserve the same field will not succeed -- YES? TODO: check this.

/**
 * 2012-03-10
 * RENAMED from LockTable to LockManager. LockTable was inappropriate because this class provides more than just the table
 * on its own. It provides locking services on ReadAccessFields/WriteAccessFields to its clients. Of course, it does still
 * store a lock table, given by the 'locks' AtomicIntegerArray.
 *
 *
 * <p>This class makes good use of bitwise operations and masking. Most/all the masking is required because the state of a lock is
 * stored with the version number (given by value of the global clock when a field which indexes into that lock in the table
 * was last modified).</p>
 *
 * <p>Note that there is not necessarily a lock per individual field. It is likely this is the case but it
 * is not guaranteed. Two distinct fields may occassionally have the same hash and therefore be using
 * the same lock in the hash table. In such a scenario there is potential for false conflicts.</p>
 */
@Exclude
public class LockManager {
	// Failure exception
	final private static TransactionException FAILURE_EXCEPTION = new TransactionException( "Failed on lock.");

	// number of locks available in hash table and mask used for helping determine index into hash table
	public static final int LOCK_TABLE_SIZE = 1<<20; // amount of locks - TODO add system property
	public static final int HASH_MASK = LOCK_TABLE_SIZE - 1; // i.e. 0x000FFFFF
	private static final AtomicIntegerArray locks =  new AtomicIntegerArray(LOCK_TABLE_SIZE); // array of 2^20 entries of 32-bit lock words

	// three states of a versioned-lock -- most-significant two bits used to represent lock state; remaining bits used for version number [Twilight-TL2 requires 2 bits for lock state]
	final private static int NUM_TS_BITS = 30; // number of bits used for timestamp
	final private static int FREE = 0x00; // most-significant two bits are 00
	final private static int RESERVED = 0x01 << NUM_TS_BITS; // most-significant two bits are 01
	final private static int LOCKED = 0x11 << NUM_TS_BITS; // most-significant two bits are 11

	// masks used for extracting value of lock and version number from versioned-lock
	final private static int LOCK_MASK = 0x11 << NUM_TS_BITS;
	final private static int VERSION_MASK = ~LOCK_MASK; // complement of LOCK_MASK

	// used for %8 and /8, respectively
	private static final int MODULO_8 = 7;
	private static final int DIVIDE_8 = 3;

	/**
	 * Self-locking --- two fields which have the same hash which are accessed within the one transaction. ???
	 *
	 * @param contextLocks I assume this is an array of locks or something that are thread-local, passed in from the Context class (hence the name 'contextLocks') -- note also that they are each a byte; that's quite a 'narrow' data type (only -128 to 127)
	 *
	 * This method also tries to prevent 'self-locking' (which I assume is where a thread tries to lock a transactional variable which is already locked by that same thread).
	 *
	 * returns false on self-locking/reserving ;-)
	 * returns true on successful reservation
	 * throws an exception on failure to reserve (due to the field having already been reserved or locked by another transaction)
	 */
	public static boolean reserve(int lockIndex, byte[] contextLocks) {
		System.out.println("lock index given is "+lockIndex);
		final int lock = locks.get(lockIndex);

		final int selfLockIndex = lockIndex >>> DIVIDE_8;
		System.out.println("selfLockIndex: "+selfLockIndex);
		final byte selfLockByte = contextLocks[selfLockIndex];
		System.out.println("selfLockByte: "+selfLockByte);
		final byte selfLockBit = (byte)(1 << (lockIndex & MODULO_8));

		// if already locked or reserved
		System.out.println("lock = "+lock);
		if( (lock & LOCK_MASK) != FREE) {
			// if we have a case of self-locking, return false to indicate so
			if((selfLockByte & selfLockBit) != 0)
				return false;
			// otherwise throw an exception
			throw FAILURE_EXCEPTION;
		}

		// attempt to set lock as reserved atomically; throw exception if the CAS fails
		boolean isReserved = locks.compareAndSet(lockIndex, lock, lock | RESERVED);
		if(!isReserved)
			throw FAILURE_EXCEPTION;

		// mark in self locks
		contextLocks[selfLockIndex] |= selfLockBit;

		// reservation successful
		return true;
	}

	/**
	 *
	 * @return <code>true</code> if lock otherwise false.
	 * @throws TransactionException in case the lock is hold by other thread.
	 */
	public static boolean lock( int lockIndex, byte[] contextLocks) throws TransactionException {
		final int lock = locks.get(lockIndex);
		final int selfLockIndex = lockIndex>>>DIVIDE_8;
		final byte selfLockByte = contextLocks[selfLockIndex];
		final byte selfLockBit = (byte)(1 << (lockIndex & MODULO_8));

		if( (lock & LOCK_MASK) == LOCKED) {  //is already locked?
			if( (selfLockByte & selfLockBit) != 0) // check for self locking
				return false;
			throw FAILURE_EXCEPTION;
		}

		// attempt to set lock as locked atomically; throw exception if the CAS fails
		boolean isLocked = locks.compareAndSet(lockIndex, lock, lock | LOCKED);
		if(!isLocked)
			throw FAILURE_EXCEPTION;

		// mark in self locks
		contextLocks[selfLockIndex] |= selfLockBit;

		// lock successful
		return true;
	}


	/**
	 * Need to check versioned-locks since a field that's in the readset of one transaction might
	 * be in the writeset of another transaction.
	 *
	 * <p>Used in two places:
	 * <ul>
	 * <li>For pre-validation of individual reads</li>
	 * <li>When validating the entire readset at the end of a transaction to determine if the
	 * readset is consistent</li>
	 * </ul>
	 * </p>
	 *
	 * @param lockIndex
	 * @param clock
	 * @return
	 */
	// throws a TransactionException if lock is held or if the versioned-lock value exceeds the given clock value (i.e. the object/field associated with the lock has been modified since the given clock value)
	public static int checkLock(int lockIndex, int clock) {
		int versionedLock = locks.get(lockIndex);

		// if clock < timestamp/version of versioned-lock || versioned-lock is marked as locked (NOTE: it is okay for the lock to be reserved; reserved fields may still have concurrent read accesses)
		if( clock < (versionedLock & VERSION_MASK) || (versionedLock & LOCK_MASK) != 0)
			throw FAILURE_EXCEPTION;

		return versionedLock;
	}

	/**
	 * <p>Used for post-validation of individual reads.</p>
	 *
	 * <p>In contrast to pre-validation, post-validation requires us to take an additional expected state of the
	 * versioned-lock for the field being read and compare with the actual state of the lock. If they are
	 * different then we throw an exception which will cause the transaction to restart.</p>
	 *
	 * @param lockIndex
	 * @param clock
	 * @param expected
	 */
	public static void checkLock(int lockIndex, int clock, int expected) {
		int lock = locks.get(lockIndex);

		// if versioned-lock bits are not the same as given by expected || clock < timestamp/version of versioned-lock || versioned-lock is marked as locked (NOTE: it is okay for the lock to be reserved; reserved fields may still have concurrent read accesses)
		if( lock != expected || clock < (lock & VERSION_MASK) || (lock & LOCK_MASK) != 0)
			throw FAILURE_EXCEPTION;
	}

	/**
	 * TODO: javadoc
	 *
	 * @param lockIndex
	 * @param contextLocks
	 */
	public static void unlock(int lockIndex, byte[] contextLocks){
		int versionedLock = locks.get(lockIndex);
		int unlockedValue = versionedLock & FREE;
		int versionValue = versionedLock & VERSION_MASK;
		locks.set(lockIndex, versionValue | unlockedValue);

		clearSelfLock(lockIndex, contextLocks);
	}

	public static void setAndReleaseLock( int lockIndex, int newClock, byte[] contextLocks){
//		int lockIndex = hash; // was: int lockIndex = hash & MASK;
		locks.set(lockIndex, newClock); // was: locks.set(lockIndex, newClock);

		clearSelfLock(lockIndex, contextLocks);
	}

	/**
	* Clears lock marker from self locking array.
	*
	* Trying to figure out
	*/
	private static void clearSelfLock( int lockIndex, byte[] contextLocks){
		// clear marker TODO might clear all bits
		contextLocks[lockIndex >>> DIVIDE_8] &= ~(1 << (lockIndex & MODULO_8));
	}
}
