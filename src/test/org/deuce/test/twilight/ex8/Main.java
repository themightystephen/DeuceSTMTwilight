package org.deuce.test.twilight.ex8;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.deuce.transaction.TransactionException;

/**
 * Test use of the restart operation in both the transactional zone and the twilight zone
 * of a transaction.
 *
 * Again, the 'twilight' attribute of the @Atomic annotation is set to true and we explicitly use
 * the Twilight.prepareCommit() API operation.
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

		final ExampleEight ex = new ExampleEight();
		Runnable transactionalRestartTask = new Runnable() {
			@Override
			public void run() {
				try {
					ex.atomicBlockWithRestartTransactionalZone();
				}
				catch(TransactionException e) {
					numMaxRetriesExceeded++;
				}
			}
		};
		Runnable twilightRestartTask = new Runnable() {
			@Override
			public void run() {
				try {
					ex.atomicBlockWithRestartTwilightZone();
				}
				catch(TransactionException e) {
					numMaxRetriesExceeded++;
				}
			}
		};
		Runnable noRestartTask = new Runnable() {
			@Override
			public void run() {
				try {
					ex.atomicBlockNoRestart();
				}
				catch(TransactionException e) {
					numMaxRetriesExceeded++;
				}
			}
		};

		for(int i = 0; i < NUM_TASKS; i++) {
			System.out.println("Execute task "+i);
			es.execute(transactionalRestartTask);
			es.execute(twilightRestartTask);
			es.execute(noRestartTask);
		}

		// shutdown executor and wait for all tasks to complete (or fail)
		es.shutdown();
		try {
			es.awaitTermination(1, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// show final value of counter
		System.out.println("Counter: "+ex.counter); // should be 500
		// show number of times the maximum number of retries was exceeded
		System.out.println("Number of times retries exceeded: "+numMaxRetriesExceeded); // should be 1000
	}
}
