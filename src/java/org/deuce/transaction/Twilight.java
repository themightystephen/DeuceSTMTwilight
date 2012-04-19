package org.deuce.transaction;

import static org.deuce.objectweb.asm.Opcodes.GETSTATIC;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.deuce.reflection.AddressUtil;
import org.deuce.transform.commons.Exclude;
import org.deuce.transform.commons.FieldsHolder;
import org.deuce.transform.commons.util.Util;
import org.deuce.transform.twilight.ExternalFieldsHolderClass;
import org.deuce.transform.twilight.method.StaticInitialiserTransformer;

/**
 * API for programmers to directly access operations of TwilightContext class.
 * In non-twilight transactions, there was no requirement for such an API because the
 * only operations needed are init(), commit(), and the field access methods
 * onReadAccess(), beforeReadAccess(), and onWriteAccess(). The places the calls to these
 * should go is pre-determined (beginning, end and on field accesses, respectively).
 *
 * For Twilight operations, functionality needs to be available to programmers at any
 * time. For example, tagging/region operations could be invoked at points during the
 * transactional part (@TwilightAtomic) [TODO: check if new tags and marking of fields with
 * tags is only allowed during transactional part of transaction and not in twilight zone].
 * Additionally, repair operations such as consistency w.r.t. a tag/region and ignoring
 * inconsistencies should be available to be invoked at any time by the programmer
 * (provided its inside the twilight zone).
 *
 * Where methods should be called by user
 * ======================================
 * reload (twilight zone; inconsistent)
 * ignoreUpdates (twilight zone; inconsistent)
 * isInconsistent (twilight zone; inconsistent)
 * isOnlyInconsistent (twilight zone; inconsistent)
 * newTag (transactional zone)
 * markField (transactional zone)
 *
 * The above categorisation makes it clear that all the repair operations are only allowed in the @TwilightInconsistent
 * method. It also indicates that the tagging and marking operations are only allowed in the @TwilightAtomic method.
 *
 * This implies the the @TwilightConsistent method shouldn't have ANY calls to the twilight API we provide to the user
 * (i.e. the twilight API is basically the explicit operations given above). However, this doesn't matter too much since
 * there is no serious problem if the user calls them. Even calling the tagging operations newTag and markField inside
 * the twilight zone has no serious problematic effect. The programmer is just wasting processor time.
 *
 * [2012-04-12 0:33 - UPDATE: many of the comments above that involve TwilightConsistent and TwilightInconsistent
 * annotations are not necessarily out of date. They're very much still true but obviously should be read
 * remembering that they assume I were taking the approach of three separately annotated methods rather than
 * one method with everything in it (transactional zone and twilight zone).]
 *
 * TODO: stop programmer calling Twilight operations if they are not inside an @Atomic method which has
 * its twilight attribute set to true.
 *
 * @author Stephen Tuttlebee
 */

/*
 * Since all calls to ANY method within an Atomic method will be modified in the instrumentation
 * process to also pass the Context as a parameter, that means that calls to the Twilight API
 * methods will also have this done to them (except for calls to the Twilight API which do not
 * occur inside an Atomic method, but that would only happen when the programmer misbehaves).
 *
 * This means I need to ensure that not just any method call has the Context passed as well -
 * calls to methods within the org.deuce.transaction.Twilight class should be left as is since
 * they are essentially calls into the STM system and obviously the STM system itself does
 * not need to be tracked.
 *
 * NOTE: the possibility of requiring the programming to pass the Context has two drawbacks.
 * One, it would make the programming interface horrible, and two, it wouldn't work because
 * during instrumentation we would have the same problem, with an extra Context argument
 * being added to each of *those* calls.
 */

@Exclude
public class Twilight {
	private static final TwilightContext context = TwilightContextDelegator.getInstance();

	// ------------------------------------------------------------------------
	// (SOME) TWILIGHT WORKFLOW OPERATIONS
	// ------------------------------------------------------------------------
	/**
	 * <p>...</p>
	 *
	 * <p>This method has been added to the API the programmer can access because I made
	 * the decision to have everything in a single method rather than spread across three
	 * annotated methods.</p>
	 */
	public static boolean prepareCommit() {
		return context.prepareCommit();
	}

	/**
	 * <p>Restarts the transaction.</p>
	 *
	 * // we're going to let the user be able to restart via this operation as the method of choice for restarting a transaction (rather than throwing a TransactionException manually)
	 *
	 * <p>This operation may be called from within the <code>@TwilightAtomic</code>,
	 * <code>@TwilightInconsistent</code>, or <code>@TwilightConsistent</code> methods.
	 * (Although calling from within <code>@TwilightConsistent</code> seems to make little
	 * sense!).</p>
	 */
	public static void restart() {
		context.restart();
	}

	// ------------------------------------------------------------------------
	// TWILIGHT REPAIR OPERATIONS (only allowed in (inconsistent) twilight zone)
	// ------------------------------------------------------------------------
	/**
	 * <p>Reloads a consistent readset.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 */
	public static void reload() {
		context.reload();
	}

	/**
	 * <p>Cause inconsistencies/updates by other transactions to be ignored when finalizing the commit.
	 * TODO: explain better</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 */
	public static void ignoreUpdates() {
		context.ignoreUpdates();
	}

	/**
	 * <p>Indicates whether the set of fields tagged with tagID contains at least one inconsistent
	 * field.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 *
	 * @param tagID A tag ID
	 * @return <code>true</code> if set of fields tagged with tagID contains at least one inconsistent field,
	 * <code>false</code> otherwise
	 */
	public static boolean isInconsistent(int tagID) {
		return context.isInconsistent(tagID);
	}

	/**
	 * <p>Indicates whether the set of fields tagged with tagID contains at least one inconsistent
	 * field, but no other tagged sets contain inconsistencies.</p>
	 *
	 * <p>This is a Twilight repair operation. It should only be called from within
	 * <code>@TwilightInconsistent</code> methods.</p>
	 *
	 * @param tagID A tag ID
	 * @return TODO: semantics of return value are not clear yet...
	 */
	public static boolean isOnlyInconsistent(int tagID) {
		return context.isOnlyInconsistent(tagID);
	}

	// ------------------------------------------------------------------------
	// TWILIGHT TAG/REGION OPERATIONS (only allowed in transactional zone)
	// ------------------------------------------------------------------------
	// Twilight Tag/Region Operations (tags can be used to mark readset entries and group them according to their semantic meaning)

	/**
	 * <p>Returns a unique integer identifier which represents a new tag, and can be passed as a
	 * parameter to the markField(), isInconsistent(), and isOnlyInconsistent() methods.</p>
	 *
	 * <p>This is a Twilight tag/region operation. It should only be called from within
	 * <code>@TwilightAtomic</code> methods.</p>
	 *
	 * @return Unique integer identifier which represents a new tag
	 */
	public static int newTag() {
		return context.newTag();
	}

//	/**
//	 * You have to provide the field name. This overloaded version of markField is for instance fields.
//	 *
//	 * <p>Given a field from the readset, we record the association of that field with the given tag.
//	 * This can be used later by the repair operations isInconsistent() and isOnlyInconsistent().</p>
//	 *
//	 * <p>This is a Twilight tag/region operation. It should only be called from within
//	 * <code>@TwilightAtomic</code> methods.</p>
//	 *
//	 * TODO: Oh dear! I'm getting the address based on the assumption that the fieldOwnerObject passed to
//	 * the method IS the fields holder object / class (?). I somehow need to know, given an instance of
//	 * the original class, if this original class is the field holding class. If it is, then I can just
//	 * access its static (synthetic) address fields easily.
//	 *
//	 * TODO: I need to check and make sure that the field the programmer is asking to mark is in the
//	 * readset. If its not, then
//	 *
//	 * Note: I expect that the use of reflection might make this a slow operation.
//	 *
//	 * @param tagID A tag ID
//	 * @param field
//	 * @param fieldOwnerObject The object owning the field (very often 'this')
//	 */
//	// TODO: this method not finished.
//	public static void markField(int tagID, String fieldName, Object fieldOwnerObject) {
//		long address;
//		Object addressFieldOwnerObject;
//
//		try {
//			// check if final field, if so, ignore the request to mark the field (we could get problems otherwise)!
//			if(Modifier.isFinal(fieldOwnerObject.getClass().getDeclaredField(fieldName).getModifiers()))
//				return;
//
//			// check if field is in readset.
//
//			String addressFieldName = Util.getAddressField(fieldName); // yes, it was used only in the transform package previously, but I still need to use it here!
//			Field addressField = fieldOwnerObject.getClass().getField(addressFieldName);
//			address = addressField.getLong(fieldOwnerObject); // ok, finally have the address itself
//		}
//		// field not in readset
//		catch (RuntimeException e) {
//			throw new RuntimeException("Attempt to mark field '"+fieldName+"' which is not in the readset. Fields must already be in readset. Try placing calls to markField() at the end of the transactional zone to ensure all fields read during the transaction are in the readset before calls to markField().",e);
//		}
//		catch (IllegalAccessException e) {
//			throw new RuntimeException(e);
//		}
//		// reflection failed (most likely cause is because given field does not exist in given owner object)
//		catch (Exception e) {
//			throw new RuntimeException("Attempt to mark a field failed. Field does not exist in given owner object.",e);
//		}
//
//		/*
//		 * Here, the field is given 'indirectly' by providing the owning object of the field (what happens if it's static field?)
//		 * and the address of the field as stored in the object's synthetic XXX__ADDRESS__ field (but that field might be stored
//		 * in one of two places...either in the main class itself (i.e. the same class as the field belongs to) or in a separate
//		 * holder class (e.g. XXXDeuceFieldsHolder)
//		 */
//		// BEWARE: the owner object of the original field is NOT NECESSARILY the owner object for the corresponding address field; if class was instrumented offline, the address fields will be in the XXXDeuceFieldsHolder class
//		// We do not know which it will be, therefore
//		// TODO: Could add an '@Instrumented' annotation which is attached to each transformed class. It would have an attribute, 'online', which is true or false, corresponding to online and offline, respectively. It is right to add this to EVERY transformed class, because some classes might be instrumented offline and some others online in the same JVM execution; we don't know and therefore we can't make assumptions. Specifying online vs. offline at the class-level gives us absolute certainty about whether the synthetic address fields are held in the same class as the original fields or in a separate fields holder class.
//		// Assuming that we ALWAYS added the @Instrumented annotation, All we would have to do would be to look at the @Instrumented annotation and if online==true, then we know the owning object for the address fields is the same as for the original fields. Otherwise, if online==false, then the address fields must be held in a separate fields holder class
//		// IDEA: we could also use the @Instrumented annotation for another purpose: to know when NOT to re-instrument a class (rather than relying on adding the @Exclude annotation to the transformed classes...it works but it's not elegant). Just add @Instrumented annotation to all classes we transform (regardless of whether any proper transformations have happened. If we put some classefiles through again that have already been transformed, there's no point putting them through again even if there's nothing to do).
//
//		// workaround for now: try both...[will this even work?! I might be forced to use the @Instrumented annotation above]
////		if() // if
////			addressFieldOwnerObject = fieldOwnerObject;
////		else
//			// hmmm, this is a String, not an Object
////			addressFieldOwnerObject = ExternalFieldsHolderClass.getFieldsHolderName(fieldOwnerObject.getClass().getSimpleName());
//
//		// ACTUALLY, I'm not sure if the 'owner' object simply refers to the original owner regardless of whether there is a separate fields holder class...
//		addressFieldOwnerObject = fieldOwnerObject;
//		context.markField(tagID, addressFieldOwnerObject, address); // FIXME: not 100% sure.
//	}
//
//	/**
//	 * This overloaded version of markField is for static/class fields.
//	 *
//	 * @param tagID
//	 * @param fieldName
//	 * @param fieldOwnerClass
//	 */
//	// TODO: this method not finished.
//	public static void markField(int tagID, String fieldName, Class<?> fieldOwnerClass) {
//		// following is how to deal with static fields... (copied from DuplicateMethod class)
//		// name of field's owner class is given by the fieldsHolderName variable
//		// the field's name is given by: StaticInitialiserTransformer.CLASS_BASE
//		// if you remember, the __CLASS_BASE__ field holds values of type Object
////		super.visitFieldInsn(GETSTATIC, fieldsHolderName,
////				StaticInitialiserTransformer.CLASS_BASE, "Ljava/lang/Object;");
//
//		// 1. get the value of __CLASS_BASE__ (held in whatever the field holder class is)
//		// 2.
//
//
//		long address;
//		Object addressFieldOwnerObject;
//
//		try {
//			// check if final field, if so, ignore the request to mark the field (we could get problems otherwise)!
//			if(Modifier.isFinal(fieldOwnerClass.getDeclaredField(fieldName).getModifiers()))
//				return;
//
//			String addressFieldName = Util.getAddressField(fieldName); // yes, it was used only in the transform package previously, but I still need to use it here!
//			Field addressField = fieldOwnerClass.getField(addressFieldName);
//			address = addressField.getLong(fieldOwnerObject); // ok, finally have the address itself
//		} catch (Exception e) {
//			e.printStackTrace();
//			throw new RuntimeException("Attempt to mark a field failed. Field does not exist in given owner object.",e);
//		}
//
//		// ACTUALLY, I'm not sure if the 'owner' object simply refers to the original owner regardless of whether there is a separate fields holder class...
//		addressFieldOwnerObject = fieldOwnerObject;
//		context.markField(tagID, addressFieldOwnerObject, address); // FIXME: not 100% sure.
//	}
}
