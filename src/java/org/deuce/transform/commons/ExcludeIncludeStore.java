package org.deuce.transform.commons;

import java.util.HashSet;

import org.deuce.transaction.AbortTransactionException;
import org.deuce.transaction.TransactionException;
import org.deuce.transform.commons.util.IgnoreTree;

/**
 * Holds the include/exclude information for the classes to instrument.
 *
 * @author guy
 * @since 1.1
 */
@Exclude //added by me
public class ExcludeIncludeStore {

	final private IgnoreTree excludeTree;
	final private IgnoreTree includeTree;
	final private HashSet<String> excludeClass = new HashSet<String>();

	final private static ExcludeIncludeStore excludeIncludeStore = new ExcludeIncludeStore();
	static{
		excludeIncludeStore.excludeClass.add("java/lang/Object");
		excludeIncludeStore.excludeClass.add("java/lang/Thread");
		excludeIncludeStore.excludeClass.add("java/lang/Throwable");
		//Always ignore TransactionException so user can explicitly throw this exception
		excludeIncludeStore.excludeClass.add(TransactionException.TRANSACTION_EXCEPTION_INTERNAL);
		excludeIncludeStore.excludeClass.add(AbortTransactionException.ABORT_TRANSACTION_EXCEPTION_INTERNAL);
	}

	/*
	 * TODO: we should ALWAYS have deuce packages in the exclude set. This would mean we don't have to annotate all the deuce classes with @Exclude individually, which can be error-prone since you can forget to do so when introducing new classes.
	 * The deuce package should be unique in general...the liklihood that someone has a project which IS the deuce project which they wish to run transactionally is very low. However, leave the possibility open by giving the user the ability to add the deuce packages to the 'include set'.
	 */

	private ExcludeIncludeStore(){

		String property = System.getProperty("org.deuce.exclude");
		if( property == null)
			property = "java.*,sun.*,org.eclipse.*,org.junit.*,junit.*";
		excludeTree = new IgnoreTree( property);

		property = System.getProperty("org.deuce.include");
		if( property == null)
			property = "";
		includeTree = new IgnoreTree( property);
	}

	public static boolean exclude(String className){
		if(excludeIncludeStore.excludeClass.contains(className))
			return true;
		return excludeIncludeStore.excludeTree.contains(className) && !excludeIncludeStore.includeTree.contains(className);
	}

}
