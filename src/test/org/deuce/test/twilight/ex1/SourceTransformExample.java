package org.deuce.test.twilight.ex1;

import java.io.IOException;

import org.deuce.reflection.AddressUtil;
import org.deuce.transaction.AbortTransactionException;
import org.deuce.transaction.TransactionException;
import org.deuce.transaction.TwilightContext;
import org.deuce.transaction.TwilightContextDelegator;

/**
 * Following is a pretend, manual instrumentation similar to what the Deuce agent should
 * do. In some places, the Java compiler is restrictive (noted in comments). Also,
 * we assume this is the result of an online instrumentation, not offline. Offline would
 * mean that the synthetic address fields would be declared and initialised in a separate
 * 'field holder' class.
 *
 * It shows a nearly equivalent source-code version of the bytecode the transformer should(?)
 * produce.
 *
 * @author Stephen Tuttlebee
 */
public class SourceTransformExample {
	private static int x = 0;

	// synthetic
	// TODO: temporarily commented out
//	public static final long x__ADDRESS__; // NOTE: Java compiler again is restrictive here; in bytecode we won't have the try catch below and therefore it is guaranteed that the field will be initialised.
	public static Object __CLASS_BASE__; // yes, deliberately not final

	// in this static/class initialiser, we would initialise ALL the synthetic (address) fields of this class
	static {
		// NOTE: Java compiler requires a try catch around these initialisations of ADDRESS fields but in bytecode I can avoid this since I KNOW the field "x" exists
		try {
			// TODO: temporarily commented out
//			x__ADDRESS__ = AddressUtil.getAddress(SourceTransformExample.class.getDeclaredField("x"));
		}
		catch (SecurityException e) { e.printStackTrace(); }
		// TODO: temporarily commented out
//		catch (NoSuchFieldException e) { e.printStackTrace(); }
	}

	/**
	 * Instrumented version of original @TwilightAtomic method, which has transaction loop
	 * and necessary local variables to perform the transaction workflow.
	 *
	 * TODO: keep annotations?? And on which methods??? The synthesised ones??? What about the other two annotated methods with TwilightConsistent and TwilightInconsistent annotations???
	 *
	 * @param s
	 * @return
	 * @throws IOException
	 */
	public static boolean foo(Object s) throws IOException{
		Throwable throwable = null;
		TwilightContext context = TwilightContextDelegator.getInstance(); // okay, I think I will need this line in both the the instrumented versions of the other annotated methods
		boolean commit = true;
		boolean result = true;
		for(int i = 10 ;i > 0 ;--i)
		{
			context.init(393, "supplied by user and placed here during transformation");
			try
			{
				result = foo(s,context);
			}
			catch(AbortTransactionException ex)
			{
				context.rollback();
				throw ex;
			}
			catch(TransactionException ex)
			{
				commit = false;
			}
			catch( Throwable ex)
			{
				throwable = ex;
			}

			if(commit)
			{
				if(context.prepareCommit()){
					if( throwable != null)
						throw (IOException)throwable;
					return result;
				}
			}
			else
			{
				context.rollback();
				commit = true;
			}
		}
		throw new TransactionException();
	}

	/**
	 * Duplicate method with instrumented field accesses.
	 *
	 * Original method was:
	 * x++;
	 *
	 * In the transformed version, we add context parameter and replace every field access with appropriate calls.
	 */
	public static boolean foo(Object s, TwilightContext c) {
//		TwilightContextDelegator.beforeReadAccess(obj, field, c);
		x++;
		return true;
	}
}
