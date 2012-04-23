package org.deuce.test.twilight.ex6;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.deuce.transaction.TransactionException;

/**
 * TEST THAT A LOCK ORDERING IS ENSURED
 * Deliberately try to force locks to be acquired in opposite orders if the implementation would
 * allow that (hopefully the implementation always forces a global ordering and so this wouldn't
 * happen).
 *
 * @author stephen
 */
public class Main {
	private static final int NUM_THREADS = 8;
	private static final int NUM_TASKS = 500; // TODO: later change back to 2000

	static int numMaxRetriesExceeded = 0;

	public static void main(String[] args) {
		// common object being worked on by threads
		final ExampleSix ex = new ExampleSix();

		// construct pool of threads
		ExecutorService es = Executors.newFixedThreadPool(NUM_THREADS);

		final Random r = new Random();

		// runnable
		Runnable task = new Runnable() {
			@Override
			public void run() {
				try {
					if(r.nextFloat() > 0.5)
						ex.incOneThenTwo();
					else
						ex.incTwoThenOne();
					System.out.println("Transaction done: counter1 = "+ex.counter1+", counter2 = "+ex.counter2);
	//				System.out.println("Transaction in thread "+ Thread.currentThread() + ". Counter has value "+ex.counter);
				}
				catch(TransactionException te) {
					te.printStackTrace();
					numMaxRetriesExceeded++;
				}
			}
		};

		// go (start executing tasks in thread pool!)
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

		// show final result of counters
		System.out.println("Counter 1: "+ex.counter1);
		System.out.println("Counter 2: "+ex.counter2);
		// show number of times the maximum number of retries was exceeded
		System.out.println("Number of times retries exceeded: "+numMaxRetriesExceeded);
	}
}
