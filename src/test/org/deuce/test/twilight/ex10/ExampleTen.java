package org.deuce.test.twilight.ex10;

import java.util.Random;

import org.deuce.Atomic;
import org.deuce.transaction.Twilight;

/**
 * Test typical use of twilight zone. That is, checking the return boolean value of prepareCommit()
 * operation and performing action based on it.
 *
 * When ConTest is NOT used, prepareCommit basically never
 * returns false. The readset never seems to become inconsistent between the point of the last
 * read (where a validation of one of the fields occurred) and the call to prepareCommit. However,
 * when I use ConTest, prepareCommit does sometimes return false on some runs. In that case,
 * it sleeps for 6 seconds to really make it obvious that the 'inconsistent branch' has been
 * taken!
 *
 * @author Stephen Tuttlebee
 *
 */
public class ExampleTen {
	private static final Random r = new Random();
	public static int explicitRestartCounter = 0;

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
			// read shared var in twilight zone
			try {
				System.out.println(counterA);
			}
			catch(Exception e) {
				e.printStackTrace();
			}
			System.out.println("Irreversible operation in thread "+Thread.currentThread()+"!");
//			try { Thread.sleep(5000); } catch (InterruptedException e) { e.printStackTrace(); }
		}
		else {
			System.out.println("Restarting in thread "+Thread.currentThread());
			//explicitRestartCounter++; // NOTE: can't use this because I'm accessing (writing) a field which I did not access in the transactional zone; the readset and writeset of a transaction must not be extended in the twilight zone.
			//try { Thread.sleep(6000); } catch (InterruptedException e) { e.printStackTrace(); } // NOTE: will not always see any *observable* sleeping because another thread can get scheduled in and so I would start seeing output from a different thread (the JVM/OS are just trying to be efficient)
			Twilight.restart(); // simple conflict resolution strategy for now
		}
	} // remember, finalizeCommit operation is implicit at the end of the method block
}
