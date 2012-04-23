package org.deuce.test.twilight.ex7;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.deuce.transaction.TransactionException;

/**
 * Test putting the explicit prepareCommit() and finalizeCommit() twilight operations in.
 * This corresponds to setting the 'twilight' attribute of the @Atomic annotation to true.
 *
 * Only test the above core twilight methods for now. Later tests will test the other
 * twilight operations. e.g.:
 * - Restart operation inside a transaction (inside transactional part OR twilight zone)
 * - inconsistencies, new tags, reload consistent readset, etc.
 * - also reproduce doubly-linked list example seen in thesis, to thoroughly test it works as expected.
 * - And do other examples too (e.g. the debugging a transaction example, where you have println inside twilight zone)
 * - (finally, something that's not actually testing, finish implementing markField())
 *
 * @author Stephen Tuttlebee
 */
public class Main {
	private static int numMaxRetriesExceeded = 0;

	private static final int NUM_THREADS = 5;
	private static final int NUM_TASKS = 500;

	public static void main(String[] args) {
		// construct pool of threads
		ExecutorService es = Executors.newFixedThreadPool(NUM_THREADS);

		final ExampleSeven ex = new ExampleSeven();
		Runnable task = new Runnable() {
			@Override
			public void run() {
				try {
					ex.atomicBlockUsingExplicitTwilightPrepareCommit();
				}
				catch(TransactionException e) {
					numMaxRetriesExceeded++;
				}
			}
		};

		for(int i = 0; i < NUM_TASKS; i++) {
			System.out.println("Execute task "+i);
			es.execute(task);
		}

		// shutdown executor and wait for all tasks to complete (or fail)
		es.shutdown();
		try {
			es.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// show final value of counter
		System.out.println("Counter: "+ex.counter);
		// show number of times the maximum number of retries was exceeded
		System.out.println("Number of times retries exceeded: "+numMaxRetriesExceeded);
	}
}
