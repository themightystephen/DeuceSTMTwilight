package org.deuce.transaction.tl2twilight.field;

import org.deuce.transaction.tl2twilight.LockManager;
import org.deuce.transform.commons.Exclude;

/**
 * <p>Represents a base class for field write access.</p>
 *
 * <p>For each non-excluded class, for each field, the Deuce Agent adds a synthetic field which uniquely
 * identifies the field by its position in the class (it is a 'static long'). For instance fields,
 * we can uniquely identify them if we take their synthetic field together with the reference to the
 * instance object which they belong to. This is why we define the hash of the field to be given by
 * combining the instance object reference and the synthetic field (it is important that we use the default
 * hash code for the instance object reference by using System.identityHashCode, rather than relying
 * on the object's hashcode, since the object may have redefined the hashCode() method and changes
 * in field values within it potentially could change the hashcode, leading to some unpredictable
 * effects). The bitwise AND with LockManager.LOCK_TABLE_SIZE is used to cut off the higher-order bits
 * of the hashcode (well, make them 0). This is because when we need to lock or reserve a ReadAccessField
 * (or WriteAccessField), the hashcode is used to index into the lock table (which has size
 * LockManager.LOCK_TABLE_SIZE).</p>
 *
 * <p>In summary, this class contains three fields:
 * <ul>
 * <li>reference: the (reference to the) object which contains the field being read or written</li>
 * <li>field: the unique long value of the synthetic field generated by the Deuce Agent</li>
 * <li>hash: this is a custom hashcode defined for the purpose of being an index into the lock table inside
 * the LockManager. I expect it also affects iteration order in data structures (this could affect the order
 * in which locks on ReadFieldAccess objects are acquired, thus avoiding potential deadlock).</li>
 * </ul>
 * </p>
 *
 * @author Guy Korland
 */
@Exclude
public class ReadFieldAccess {
	protected Object reference;
	protected long field;
	private int hash;

	// TODO: work out whether to remove this constructor or not. If I do then I will need refactor its subclasses appropriately
	public ReadFieldAccess(){}

	public ReadFieldAccess(Object reference, long field){
		init(reference, field);
	}

	public void init(Object reference, long field){
		this.reference = reference;
		this.field = field;
		this.hash = (System.identityHashCode(reference) + (int)field) & LockManager.LOCK_TABLE_SIZE; // was: this.hash = (System.identityHashCode(reference) + (int)field) & LockManager.MASK;
	}

	@Override
	public boolean equals(Object obj){
		ReadFieldAccess other = (ReadFieldAccess)obj;
		return reference == other.reference && field == other.field;
	}

	@Override
	final public int hashCode(){
		return hash;
	}

	public void clear(){
		reference = null;
	}
}