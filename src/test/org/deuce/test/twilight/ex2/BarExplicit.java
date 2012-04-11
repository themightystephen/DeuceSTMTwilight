package org.deuce.test.twilight.ex2;
import java.io.IOException;
import java.util.Random;

import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transaction.TransactionException;

/**
 * <p>In this manual translation of foo(), we are assuming that foo() was marked as Atomic by the programmer and so
 * we do NOT keep the original method around.</p>
 *
 * <p>To exercise the Context API properly we need to add some (instance) fields which are accessed by the original
 * method foo(). Having real accesses allows us to place calls to synthetic getters and setters.</p>
 *
 * @author stephen
 *
 */
public class BarExplicit {
	private int x = 0;
	public static final long __CLASS_BASE__ = 2;
	public static final long x__ADDRESS__ = 1; // unique 'address' for the field x (uniquely identifies a field when combined with this object instance)
	// for each instance field in any loaded class, Deuce adds a synthetic constants field that represents the relative position of the field in the class

	// original method instrumented (because it was marked as Atomic by the programmer)
	// we assume the original method threw some particular kind of exception, let's say an IOException
	public int foo(Object s) throws IOException {
		Throwable throwable = null;
		Context context = ContextDelegator.getInstance();
		boolean commit = true;
		Integer result = null;

		for (int i = 64; i > 0; --i) {
			context.init(1, "atomic foo!"); // pass it a fake atomicBlockId and meta information about the current atomic block
			try {
//				System.out.println("getting here?");
				result = foo(s, context);
			}
			catch(TransactionException ex) {
				// must rollback
				commit = false;
			}
			catch(Throwable ex) {
				throwable = ex;
			}
			System.out.println("commit is "+commit);
			// try to commit
			if(commit) {
				if(context.commit()) {
					if(throwable == null) {
//						System.out.println("not getting here?");
						return result;
					}
					// rethrow application exception
					throw (IOException)throwable;
				}
				else {
					System.out.println("but context.commit() failed");
				}
			}
			else {
				context.rollback();
				commit = true;
			}
		} // retry loop
		throw new TransactionException("aborted 64 times!"); // if we get to here then we've aborted 64 times!
	}

	// synthetic duplicate method
	public int foo(Object s, Context c) throws IOException {
		// every now and again our original method would throw an IOException; the rest of the time it behaves fine.
		Random r = new Random();
		if(r.nextFloat() < 0.0) { // TODO: change back to 0.05 at some point
			throw new IOException();
		}

		int y = x__Getter$(c) + 1;
		x__Setter$(y,c);
		return x__Getter$(c);
	}

	// synthetic getter for the x field
	public int x__Getter$(Context c) {
		c.beforeReadAccess(this, x__ADDRESS__);
		return c.onReadAccess(this, x, x__ADDRESS__);
	}

	// synthetic setter for the x field
	public void x__Setter$(int v, Context c) {
		c.onWriteAccess(this, v, x__ADDRESS__);
	}
}
