package org.deuce.test.twilight.ex9;

import java.util.Random;

import org.deuce.Atomic;
import org.deuce.transaction.Twilight;

/**
 * Test typical use of twilight zone.
 *
 * @author stephen
 *
 */
public class ExampleNine {
	private static final Random r = new Random();

	public int counterA = 0;
	public int counterB = 0;
	public int counterC = 0;
	private Object o = new Object();

	/**
	 * Transaction where we make use Twilight zone's ability to perform irrevocable operations
	 * inside the twilight zone (provided the readset is consistent).
	 *
	 * TODO: handle situation where user might put try catch around their code with a more general
	 * exception than I have (TransactionException)...what do we do then? We still want our exception
	 * to propagate or whatever. Need to think about this.
	 */
	@Atomic(twilight=true)
	public void atomicBlockUsingTwilightZone() {
		counterA = counterA + 1;
		counterB = counterB + 2;
		counterC = counterC + counterA;

		// NOTE: a NullPointerException occurs inside beforeReadAccess if its parameter 'obj' is accessed.
		// This only seems to occur for reference types (i.e. objects)
		// A println statement in beforeReadAccess accessing 'obj' is commented out -- uncomment it to see the effects
		// Also, note that making o final would cause the exception to not occur because final field accesses do not get instrumented with beforeReadAccess and onReadAccess calls
		try {
			o = new Object();
		}
		catch(NullPointerException e) {
			e.printStackTrace();
		}

		// increase liklihood of race condition
		// -- basically, I've got this sleep here with the aim to force an inconsistency to be found in the prepareCommit.
		// Unfortunately this technique isn't really working... -- I need a different way. Maybe just have two separate
		// @Atomic methods which access a common piece of shared data, with appropriate sleeping to make it extremely
		// likely that the prepareCommit will return false -- also, only having two transactions will make it easier
		// for me to reason about in my head
		//try { Thread.sleep(r.nextInt(3)); } catch (InterruptedException e1) { e1.printStackTrace(); }

		boolean consistent = Twilight.prepareCommit();
		/* twilight zone */
		if(consistent) {
			System.out.println("Irreversible operation in thread "+Thread.currentThread()+"!");
		}
		else {
			System.out.println("Restarting in thread "+Thread.currentThread());
			try { Thread.sleep(6000); } catch (InterruptedException e) { e.printStackTrace(); }
			Twilight.restart(); // simple conflict resolution strategy for now
		}
	} // remember, finalizeCommit operation is implicit at the end of the method block
}
