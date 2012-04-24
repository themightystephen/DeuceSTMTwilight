package org.deuce.test.twilight.ex8;

import org.deuce.Atomic;
import org.deuce.transaction.Twilight;

public class ExampleEight {
	public int counter = 0;

	/**
	 * Transaction which restarts in main transactional zone. We set a retry limit.
	 */
	@Atomic(twilight=true,retries=15)
	public void atomicBlockWithRestartTransactionalZone() {
		counter++;

		// restart within main transactional zone
		Twilight.restart();

		boolean consistent = Twilight.prepareCommit();
		/* twilight zone */

	} // remember, finalizeCommit operation is implicit at the end of the method block

	/**
	 * Transaction which restarts in twilight zone. We set a retry limit.
	 */
	@Atomic(twilight=true,retries=15)
	public void atomicBlockWithRestartTwilightZone() {
		counter++;

		boolean consistent = Twilight.prepareCommit();
		/* twilight zone */

		// restart within twilight zone
		Twilight.restart();

	} // remember, finalizeCommit operation is implicit at the end of the method block

	/**
	 * Transaction which doesn't restart. The purpose of this is to test that the restarting
	 * of other transactions does not affect the end result of this transaction.
	 */
	@Atomic(twilight=true)
	public void atomicBlockNoRestart() {
		counter++;

		boolean consistent = Twilight.prepareCommit();
		/* twilight zone */

	} // remember, finalizeCommit operation is implicit at the end of the method block
}
